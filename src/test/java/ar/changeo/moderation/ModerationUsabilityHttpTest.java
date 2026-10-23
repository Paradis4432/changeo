package ar.changeo.moderation;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes={ChangeoApplication.class,ModerationUsabilityHttpTest.Configuration.class},properties={"spring.flyway.schemas=moderation_usability_http_test","spring.flyway.default-schema=moderation_usability_http_test","spring.jpa.properties.hibernate.default_schema=moderation_usability_http_test","spring.datasource.hikari.schema=moderation_usability_http_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.mfa-key=.runtime/moderation-usability-http-mfa.key","changeo.files-root=.runtime/moderation-usability-http-artifacts"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ModerationUsabilityHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModerationPostgresTest.TestClock clock;
    @MockitoBean TokenDelivery delivery;
    private final Map<String,String> tokens=new ConcurrentHashMap<>();
    private MockHttpSession operator;
    private Properties enrollment;
    @TestConfiguration static class Configuration {
        @Bean @Primary ModerationPostgresTest.TestClock testClock() { return new ModerationPostgresTest.TestClock(); }
    }
    @BeforeEach void setup() throws Exception {
        clock.advance(61);jdbc.update("delete from moderation_task");jdbc.update("update workbench_resource set approved_revision=null");reset(delivery);tokens.clear();
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation->{tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2));return true;});
        Path file=Path.of(".runtime/m1-http-enrollment-"+UUID.randomUUID());
        try {
            operators.provision("m1httpoperator"+UUID.randomUUID()+"@example.test",Set.of("IDENTITY_ADMIN","MODERATION_REVIEWER","MODERATION_AUDITOR","RESTRICTION_ADMIN"),file);
            enrollment=new Properties();try(var input=Files.newBufferedReader(file)){enrollment.load(input);}
        } finally {Files.deleteIfExists(file);}
        operator=login(enrollment.getProperty("contact"),enrollment.getProperty("password"));
    }
    private MockHttpSession login(String contact,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("username",contact).param("password",password)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
    }
    private void authenticateOperator() throws Exception {
        mvc.perform(post("/moderation/operator/mfa").session(operator).with(csrf()).param("code",totp(enrollment.getProperty("totpBase32")))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/moderation/operator/reauth").session(operator).with(csrf()).param("password",enrollment.getProperty("password"))).andExpect(redirectedUrl("/moderation/operator"));
    }
    private MockHttpSession author() throws Exception {
        String contact="m1http"+UUID.randomUUID()+"@example.test";String password="Synthetic-password-12345";
        UUID id=accounts.register(contact,password).accountId().value();accounts.verifyContact(id,tokens.get(id+":CONTACT"));
        mvc.perform(post("/admin/accounts/"+id+"/eligibility").session(operator).with(csrf()).param("kind","AGE").param("age","30")).andExpect(status().is3xxRedirection());
        return login(contact,password);
    }
    private UUID draft(MockHttpSession session,String text,String label) throws Exception {
        String location=mvc.perform(multipart("/moderation/drafts").session(session).with(csrf()).param("command",UUID.randomUUID().toString()).param("text",text).param("label",label).param("audience","PUBLIC")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring(location.lastIndexOf('/')+1));
    }
    private UUID revision(UUID resource) { return jdbc.queryForObject("select current_revision from workbench_resource where id=?",UUID.class,resource); }
    private void submit(MockHttpSession session,UUID resource) throws Exception { mvc.perform(post("/moderation/submit").session(session).with(csrf()).param("resource",resource.toString()).param("revision",revision(resource).toString())).andExpect(status().is3xxRedirection()); }
    @Test void shouldReturnInvalidFocusedProofsToBlankLinkedRetryForms() throws Exception {
        mvc.perform(post("/moderation/operator/mfa").session(operator).with(csrf()).param("code","wrong-secret"))
                .andExpect(status().isForbidden()).andExpect(view().name("moderation-operator-signin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"#code\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-secret"))));
        mvc.perform(post("/moderation/operator/reauth").session(operator).with(csrf()).param("password","wrong-secret"))
                .andExpect(status().isForbidden()).andExpect(view().name("moderation-operator-signin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"#password\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-secret"))));
        mvc.perform(get("/moderation/operator").session(operator)).andExpect(status().isForbidden());
        authenticateOperator();
        mvc.perform(get("/moderation/operator").session(operator)).andExpect(status().isOk());
    }
    @Test void shouldOfferAppealsOnlyAfterSubmissionAndKeepCaseNavigationOwnerNeutral() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"synthetic:clear","GENERAL");
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Abrir apelación"))));
        submit(author,resource);
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Abrir apelación")));
        mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","APPEAL").param("reason","OTHER")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/moderation/cases").session(author)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/marketplace/mine")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/moderation/own/"+resource))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/moderation/evidence"))));
    }
    @Test void shouldRecoverOnlySafeExactCaseFieldsAndPreserveServiceReplay() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"synthetic:clear","GENERAL");submit(author,resource);worker.processOne();worker.processOne();
        String command=UUID.randomUUID().toString();String note="<script>safe-note</script>";long before=jdbc.queryForObject("select count(*) from moderation_case",Long.class);
        mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",command).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","REPORT").param("reason","INVALID").param("note",note))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-case-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;safe-note&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(command)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"#reason\"")));
        assertThat(jdbc.queryForObject("select count(*) from moderation_case",Long.class)).isEqualTo(before);
        for(int i=0;i<2;i++) mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",command).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","REPORT").param("reason","OTHER").param("note",note)).andExpect(redirectedUrl("/moderation/cases"));
        assertThat(jdbc.queryForObject("select count(*) from moderation_case",Long.class)).isEqualTo(before+1);
        mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",command).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","REPORT").param("reason","OTHER").param("note","changed")).andExpect(status().isForbidden());
    }
    @Test void shouldLinkExactCaseEvidenceOnlyForReviewers() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"synthetic:clear","GENERAL");submit(author,resource);worker.processOne();worker.processOne();
        mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","REPORT").param("reason","OTHER").param("note","private-note")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/moderation/operator").session(operator)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("kind=REQUEST_BODY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Inspeccionar evidencia del caso")));
        mvc.perform(get("/moderation/audit").session(operator)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/moderation/evidence"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-note"))));
    }

    @Test void shouldBoundOwnedAppealNoteAndDenyForeignUnsubmittedHiddenAndStaleRecovery() throws Exception {
        authenticateOperator();var author=author();var stranger=author();var resource=draft(author,"synthetic:clear","GENERAL");
        String note="sensitive-request-note";
        rejectedCase(author,resource,revision(resource),"APPEAL",note,403);
        submit(author,resource);worker.processOne();worker.processOne();
        rejectedCase(stranger,resource,revision(resource),"APPEAL",note,403);
        var adult=draft(author,"synthetic:adult","ADULT");submit(author,adult);worker.processOne();worker.processOne();
        rejectedCase(author,adult,revision(adult),"APPEAL",note,403);
        UUID previous=revision(resource);
        long version=jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource);
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("version",String.valueOf(version)).param("text","synthetic:tattoo").param("label","GENERAL").param("audience","PUBLIC")).andExpect(status().is3xxRedirection());
        submit(author,resource);worker.processOne();worker.processOne();
        rejectedCase(author,resource,previous,"REPORT",note,403);
        long before=jdbc.queryForObject("select count(*) from moderation_case",Long.class);
        var response=mvc.perform(post("/moderation/cases").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","APPEAL").param("reason","OTHER").param("note","x".repeat(2001)))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-case-form")).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("x".repeat(2000)).doesNotContain("x".repeat(2001));
        assertThat(jdbc.queryForObject("select count(*) from moderation_case",Long.class)).isEqualTo(before);
        mvc.perform(post("/moderation/cases").session(author).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("kind","APPEAL").param("reason","INVALID").param("note",note))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(note))));
    }

    private void rejectedCase(MockHttpSession session,UUID resource,UUID revision,String kind,String note,int status) throws Exception {
        long before=jdbc.queryForObject("select count(*) from moderation_case",Long.class);
        mvc.perform(post("/moderation/cases").session(session).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",revision.toString()).param("kind",kind).param("reason","INVALID").param("note",note))
                .andExpect(status().is(status)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(note))));
        assertThat(jdbc.queryForObject("select count(*) from moderation_case",Long.class)).isEqualTo(before);
    }

    private String totp(String secret) throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
        for(char c:secret.toCharArray()) {buffer=(buffer<<5)|alphabet.indexOf(c);bits+=5;if(bits>=8){bits-=8;decoded.write((buffer>>bits)&255);}}
        Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decoded.toByteArray(),"HmacSHA1"));byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond()/30).array());int offset=digest[digest.length-1]&15;
        int value=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;return String.format(Locale.ROOT,"%06d",value%1000000);
    }
}
