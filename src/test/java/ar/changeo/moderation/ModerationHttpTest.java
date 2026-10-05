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

@SpringBootTest(classes={ChangeoApplication.class,ModerationHttpTest.Configuration.class},properties={"spring.flyway.schemas=moderation_http_test","spring.flyway.default-schema=moderation_http_test","spring.jpa.properties.hibernate.default_schema=moderation_http_test","spring.datasource.hikari.schema=moderation_http_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.mfa-key=.runtime/moderation-http-mfa.key","changeo.files-root=.runtime/moderation-http-artifacts"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ModerationHttpTest {
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
    @Test void shouldRenderSpanishWorkflowAndApplyExactApprovalToEveryPublicProjection() throws Exception {
        authenticateOperator();MockHttpSession author=author();UUID resource=draft(author,"synthetic:tattoo","GENERAL");
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Borrador guardado; falta enviar a revisión")));
        for(String suffix:List.of("","/preview","/notification")) { mvc.perform(get("/content/"+resource+suffix)).andExpect(status().isForbidden()); }
        mvc.perform(get("/content")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic:tattoo"))));
        submit(author,resource);assertThat(worker.processOne()).isTrue();assertThat(worker.processOne()).isTrue();
        for(String suffix:List.of("","/preview","/notification")) { mvc.perform(get("/content/"+resource+suffix)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("synthetic:tattoo"))); }
        mvc.perform(get("/content").param("q","tattoo")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("synthetic:tattoo")));
        mvc.perform(get("/content/files/"+UUID.randomUUID()).param("resource",resource.toString()).param("revision",revision(resource).toString()).param("digest","malformed")).andExpect(status().isBadRequest());
        mvc.perform(post("/content/"+resource)).andExpect(status().isForbidden());
        mvc.perform(post("/moderation/submit").session(author).param("resource",resource.toString()).param("revision",revision(resource).toString())).andExpect(status().isForbidden());
    }
    @Test void shouldDenyPasswordOnlyAuditorPayloadAndExpiredProofAndEscapeUntrustedEvidence() throws Exception {
        mvc.perform(get("/moderation/operator").session(operator)).andExpect(status().isForbidden());authenticateOperator();MockHttpSession author=author();
        UUID resource=draft(author,"<script>alert('fixture')</script>","GENERAL");submit(author,resource);worker.processOne();
        mvc.perform(get("/moderation/evidence").session(operator).param("kind","REQUEST_BODY").param("resource",resource.toString()).param("revision",revision(resource).toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))));
        mvc.perform(get("/moderation/audit").session(operator)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert('fixture')"))));
        clock.advance(601);mvc.perform(get("/moderation/operator").session(operator)).andExpect(status().isForbidden());mvc.perform(get("/moderation/audit").session(operator)).andExpect(status().isForbidden());
    }
    @Test void shouldBoundRemoteSourceBeforeMutationPreserveStatusAndExpireWindow() throws Exception {
        authenticateOperator();MockHttpSession author=author();clock.advance(61);
        UUID resource=draft(author,"synthetic:clear","GENERAL");
        for(int index=0;index<19;index++) { mvc.perform(post("/moderation/submit").session(author).with(csrf()).param("resource",resource.toString()).param("revision",revision(resource).toString())).andExpect(status().is3xxRedirection()); }
        mvc.perform(post("/moderation/submit").session(author).with(csrf()).param("resource",resource.toString()).param("revision",revision(resource).toString())).andExpect(status().isTooManyRequests());
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(status().isOk());mvc.perform(get("/support").session(author)).andExpect(status().isOk());
        clock.advance(61);submit(author,resource);assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,revision(resource))).isEqualTo(1);
    }
    @Test void shouldBoundActorAcrossIndependentRemoteSourcesAndRejectStaleSessionOnNextRequest() throws Exception {
        authenticateOperator();MockHttpSession author=author();clock.advance(61);
        for(int index=0;index<10;index++) {
            final String source="127.0.1."+(index+1);
            mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).with(request->{request.setRemoteAddr(source);return request;}).param("command",UUID.randomUUID().toString()).param("text","synthetic:clear").param("label","GENERAL").param("audience","PUBLIC")).andExpect(status().is3xxRedirection());
        }
        long before=jdbc.queryForObject("select count(*) from workbench_revision",Long.class);
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).with(request->{request.setRemoteAddr("127.0.2.1");return request;}).param("command",UUID.randomUUID().toString()).param("text","synthetic:clear").param("label","GENERAL").param("audience","PUBLIC")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(before);
        var context=(org.springframework.security.core.context.SecurityContext)author.getAttribute("SPRING_SECURITY_CONTEXT");var principal=(ar.changeo.security.AccountPrincipal)context.getAuthentication().getPrincipal();
        accounts.forgot(principal.contact());accounts.reset(tokens.get(principal.id()+":RECOVERY"),"Synthetic-new-password-12345");
        mvc.perform(get("/moderation").session(author)).andExpect(redirectedUrl("/login?expired"));
    }

    private String totp(String secret) throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
        for(char c:secret.toCharArray()) {buffer=(buffer<<5)|alphabet.indexOf(c);bits+=5;if(bits>=8){bits-=8;decoded.write((buffer>>bits)&255);}}
        Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decoded.toByteArray(),"HmacSHA1"));byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond()/30).array());int offset=digest[digest.length-1]&15;
        int value=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;return String.format(Locale.ROOT,"%06d",value%1000000);
    }
}
