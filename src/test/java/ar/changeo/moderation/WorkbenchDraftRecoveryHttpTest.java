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

@SpringBootTest(classes={ChangeoApplication.class,WorkbenchDraftRecoveryHttpTest.Configuration.class},properties={"spring.flyway.schemas=workbench_recovery_http_test","spring.flyway.default-schema=workbench_recovery_http_test","spring.jpa.properties.hibernate.default_schema=workbench_recovery_http_test","spring.datasource.hikari.schema=workbench_recovery_http_test","changeo.synthetic-evidence=true","changeo.synthetic-moderation=true","changeo.moderation-worker=false","changeo.mfa-key=.runtime/workbench-recovery-http-mfa.key","changeo.files-root=.runtime/workbench-recovery-http-artifacts"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class WorkbenchDraftRecoveryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModerationPostgresTest.TestClock clock;
    @MockitoBean TokenDelivery delivery;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkbenchService workbench;
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

    @Test void shouldRecoverEscapedCallerTextWhenLabelIsInvalid() throws Exception {
        authenticateOperator();var author=author();String text="<script>caller replacement</script>";
        long before=jdbc.queryForObject("select count(*) from workbench_revision",Long.class);
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text",text).param("label","INVALID").param("audience","PUBLIC"))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-draft-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;caller replacement&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"#label\"")));
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(before);
    }
    @Test void shouldRecoverMalformedParticipantWithoutLosingText() throws Exception {
        authenticateOperator();var author=author();
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text","caller text retained").param("label","GENERAL").param("audience","PRIVATE").param("participant","malformed"))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-draft-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("caller text retained")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"#participant\"")));
    }
    @Test void shouldRecoverReplacementForOwnHiddenUnsubmittedEditWithoutReadingOldText() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"stored hidden unique body","GENERAL");
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("stored hidden unique body"))));
        long version=jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource);
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("version",String.valueOf(version)).param("text","safe caller replacement").param("label","INVALID").param("audience","PUBLIC"))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-draft-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("safe caller replacement")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("stored hidden unique body"))));
    }
    @Test void shouldBoundUtf8WithoutSplittingCharactersAndPreserveValidOriginalText() throws Exception {
        authenticateOperator();var author=author();String within="😀".repeat(8192),tooLong=within+"😀";
        String command=UUID.randomUUID().toString();
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",command).param("text",tooLong).param("label","GENERAL").param("audience","PUBLIC"))
                .andExpect(status().isBadRequest()).andExpect(view().name("moderation-draft-form"))
                .andExpect(result->{var form=(ar.changeo.moderation.web.ModerationForms.Draft)result.getModelAndView().getModel().get("form");assertThat(form.text()).isEqualTo(within);assertThat(form.command()).isEqualTo(command);});
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",command).param("text",within).param("label","GENERAL").param("audience","PUBLIC")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select text from workbench_revision v join workbench_receipt r on r.revision_id=v.id where r.command_id=?",String.class,UUID.fromString(command))).isEqualTo(within);
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text","valid-prefix\uD800").param("label","GENERAL").param("audience","PUBLIC"))
                .andExpect(status().isBadRequest()).andExpect(result->assertThat(((ar.changeo.moderation.web.ModerationForms.Draft)result.getModelAndView().getModel().get("form")).text()).isEqualTo("valid-prefix"));
    }
    @Test void shouldDenyUnestablishedForeignStaleAndCsrfContextsWithoutEchoOrMutation() throws Exception {
        authenticateOperator();var author=author();var stranger=author();var resource=draft(author,"stored secret","GENERAL");
        long version=jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource);
        long before=jdbc.queryForObject("select count(*) from workbench_revision",Long.class);
        String text="caller text must not echo";
        for(var field:List.of(Map.entry("command","invalid"),Map.entry("resource","invalid"),Map.entry("version","invalid"),Map.entry("subject","invalid"),Map.entry("audience","invalid"))) {
            var request=multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text",text).param("label","INVALID").param("audience","PUBLIC");
            request.param(field.getKey(),field.getValue());
            if(field.getKey().equals("command")) { request= multipart("/moderation/drafts").session(author).with(csrf()).param("command","invalid").param("text",text).param("label","INVALID").param("audience","PUBLIC"); }
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(view().name("moderation-draft-error")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(text))));
        }
        for(var session:List.of(author,stranger)) {
            mvc.perform(multipart("/moderation/drafts").session(session).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("version",Long.toString(session==author?version-1:version)).param("text",text).param("label","INVALID").param("audience","PUBLIC"))
                    .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(text))));
        }
        mvc.perform(multipart("/moderation/drafts").session(author).param("command",UUID.randomUUID().toString()).param("text",text).param("label","INVALID").param("audience","PUBLIC"))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(text))));
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(before);
    }
    @Test void shouldKeepValidSameCommandOldVersionUploadReplayAndIgnoredEditContext() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"synthetic:clear","GENERAL");long version=jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource);
        String command=UUID.randomUUID().toString();var upload=new MockMultipartFile("upload","safe.txt","text/plain","synthetic:clear".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int index=0;index<2;index++) {
            mvc.perform(multipart("/moderation/drafts").file(upload).session(author).with(csrf()).param("command",command).param("resource",resource.toString()).param("version",String.valueOf(version)).param("text","  original untrimmed text  ").param("label","GENERAL").param("audience","PUBLIC").param("subject",UUID.nameUUIDFromBytes(new byte[]{1}).toString()))
                    .andExpect(redirectedUrl("/moderation/own/"+resource));
        }
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision where resource_id=?",Long.class,resource)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from files_artifact where resource_id=?",Long.class,resource)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workbench_receipt where command_id=?",Long.class,UUID.fromString(command))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select text from workbench_revision where id=(select current_revision from workbench_resource where id=?)",String.class,resource)).isEqualTo("  original untrimmed text  ");
        mvc.perform(multipart("/moderation/drafts").file(upload).session(author).with(csrf()).param("command",command).param("resource",resource.toString()).param("version",String.valueOf(version)).param("text","changed").param("label","GENERAL").param("audience","PUBLIC"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision where resource_id=?",Long.class,resource)).isEqualTo(2);
    }
    @Test void shouldRecoverFileOwnerRejectionWithOriginalStatusReselectionAndNoEffects() throws Exception {
        authenticateOperator();var author=author();long revisions=jdbc.queryForObject("select count(*) from workbench_revision",Long.class),files=jdbc.queryForObject("select count(*) from files_artifact",Long.class);
        for(var upload:List.of(new MockMultipartFile("upload","bad.png","image/png",new byte[]{1,2,3}),new MockMultipartFile("upload","bad.txt","text/plain",new byte[]{(byte)0xff}),new MockMultipartFile("upload","active.html","text/html",new byte[]{1}))) {
            mvc.perform(multipart("/moderation/drafts").file(upload).session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text","safe replacement remains").param("label","GENERAL").param("audience","PUBLIC"))
                    .andExpect(status().isForbidden()).andExpect(view().name("moderation-draft-form"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("safe replacement remains")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("volvé a seleccionar el archivo")));
        }
        assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(revisions);assertThat(jdbc.queryForObject("select count(*) from files_artifact",Long.class)).isEqualTo(files);
    }


    @Test void shouldKeepUnexpectedDatabaseFailureAndStoredDraftAfterRejectedSubmission() throws Exception {
        authenticateOperator();var author=author();var resource=draft(author,"synthetic:clear","GENERAL");long before=jdbc.queryForObject("select count(*) from workbench_revision",Long.class);
        mvc.perform(post("/moderation/submit").session(author).with(csrf()).param("resource",resource.toString()).param("revision",UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(view().name("moderation-draft-error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/moderation\"")));
        mvc.perform(get("/moderation/own/"+resource).session(author)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Enviar a revisión")));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic technical outage")).when(workbench).save(any());
        assertThatThrownBy(()->mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command",UUID.randomUUID().toString()).param("text","valid text").param("label","GENERAL").param("audience","PUBLIC")))
                .hasRootCauseInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        reset(workbench);assertThat(jdbc.queryForObject("select count(*) from workbench_revision",Long.class)).isEqualTo(before);
    }
    @Test void shouldUseOnlyTrustedExactPostErrorDispatchWithoutReadingMultipartParameters() {
        var request=mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/moderation/drafts");
        when(request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_METHOD)).thenReturn("POST");
        var resolver=new ar.changeo.moderation.web.WorkbenchUploadErrors();
        var view=resolver.resolveErrorView(request,org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,Map.of());
        assertThat(view.getViewName()).isEqualTo("moderation-draft-error");assertThat(view.getStatus().value()).isEqualTo(413);
        when(request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_METHOD)).thenReturn("GET");assertThat(resolver.resolveErrorView(request,org.springframework.http.HttpStatus.CONTENT_TOO_LARGE,Map.of())).isNull();
        when(request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_METHOD)).thenReturn("POST");when(request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/conversations/messages");assertThat(resolver.resolveErrorView(request,org.springframework.http.HttpStatus.CONTENT_TOO_LARGE,Map.of())).isNull();
        verify(request,never()).getParameter(anyString());verify(request,never()).getParameterMap();verify(request,never()).getQueryString();verify(request,never()).getHeader(anyString());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={" 0 ","0x0","0 0","#0"})
    void shouldPreserveOriginalSpringUuidAndVersionConversion(String version) throws Exception {
        authenticateOperator();var author=author();String command=UUID.randomUUID().toString();
        mvc.perform(multipart("/moderation/drafts").session(author).with(csrf()).param("command"," "+command+" ").param("version",version).param("text","  original text remains  ").param("label","GENERAL").param("audience","PUBLIC").param("subject"," ").param("participant"," "))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select text from workbench_revision v join workbench_receipt r on r.revision_id=v.id where r.command_id=?",String.class,UUID.fromString(command))).isEqualTo("  original text remains  ");
    }

    @Test void shouldPreserveFirstTypedValuesAndOriginalStringsWithExactUploadReplay() throws Exception {
        authenticateOperator();var author=author();var before=effectCounts();
        String command=UUID.randomUUID().toString(),ignored=UUID.randomUUID().toString();UUID resource=null;
        var upload=new MockMultipartFile("upload","safe.txt","text/plain","synthetic:clear".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,List<String>> saved=null;
        for(int attempt=0;attempt<2;attempt++) {
            String location=mvc.perform(multipart("/moderation/drafts").file(upload).session(author).with(csrf())
                    .param("command",command,attempt==0?command:UUID.randomUUID().toString())
                    .param("resource","",UUID.randomUUID().toString()).param("version","0","99")
                    .param("subject"," ",UUID.randomUUID().toString()).param("participant","",UUID.randomUUID().toString())
                    .param("text","  first, literal  ","  second  ").param("label","GENERAL").param("audience","PRIVATE"))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            if(attempt==0) { resource=UUID.fromString(location.substring(location.lastIndexOf('/')+1));saved=draftEffects(); }
            else { assertThat(location).isEqualTo("/moderation/own/"+resource);assertThat(draftEffects()).isEqualTo(saved); }
        }
        assertThat(jdbc.queryForObject("select text from workbench_revision where resource_id=?",String.class,resource)).isEqualTo("  first, literal  ,  second  ");
        assertThat(jdbc.queryForObject("select count(*) from workbench_receipt where command_id=?",Long.class,UUID.fromString(command))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workbench_member where resource_id=?",Long.class,resource)).isZero();
        long version=jdbc.queryForObject("select version from workbench_resource where id=?",Long.class,resource);
        String edit=UUID.randomUUID().toString();
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(multipart("/moderation/drafts").file(upload).session(author).with(csrf())
                    .param("command",edit,UUID.randomUUID().toString()).param("resource",resource.toString(),UUID.randomUUID().toString())
                    .param("version",Long.toString(version),"0").param("subject",ignored,UUID.randomUUID().toString())
                    .param("participant",ignored,UUID.randomUUID().toString()).param("text","  unchanged edit, literal  ")
                    .param("label","GENERAL").param("audience","PRIVATE"))
                    .andExpect(redirectedUrl("/moderation/own/"+resource));
            if(attempt==0) { saved=draftEffects(); }
            else { assertThat(draftEffects()).isEqualTo(saved); }
        }
        var after=effectCounts();
        for(String table:after.keySet()) {
            long delta=switch(table) {
                case "workbench_resource" -> 1;
                case "workbench_revision","workbench_receipt","workbench_attachment","files_artifact" -> 2;
                default -> 0;
            };
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table)+delta);
        }
        assertThat(jdbc.queryForObject("select text from workbench_revision where id=(select current_revision from workbench_resource where id=?)",String.class,resource)).isEqualTo("  unchanged edit, literal  ");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("malformedFirstTypedValues")
    void shouldRejectMalformedFirstAndSingleLiteralCommaWithoutEffects(String field,String[] values) throws Exception {
        authenticateOperator();var author=author();var before=draftEffects();
        var request=multipart("/moderation/drafts").session(author).with(csrf()).param("text","caller negative text").param("label","GENERAL").param("audience","PRIVATE");
        if(!field.equals("command")) { request.param("command",UUID.randomUUID().toString()); }
        request.param(field,values);
        mvc.perform(request).andExpect(status().isBadRequest());
        assertThat(draftEffects()).isEqualTo(before);
    }
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformedFirstTypedValues() {
        var values=new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for(String field:List.of("command","resource","version","subject","participant")) {
            String valid=field.equals("version")?"0":UUID.randomUUID().toString();
            values.add(org.junit.jupiter.params.provider.Arguments.of(field,new String[]{"malformed",valid}));
            values.add(org.junit.jupiter.params.provider.Arguments.of(field,new String[]{valid+","+valid}));
        }
        return values.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ABSENT","EMPTY","REPEATED_EMPTY","WHITESPACE"})
    void shouldPreserveOriginalMissingEmptyAndWhitespaceVersion(String variant) throws Exception {
        authenticateOperator();var author=author();var before=draftEffects();String command=UUID.randomUUID().toString();
        var request=multipart("/moderation/drafts").session(author).with(csrf()).param("command",command).param("text","version compatibility").param("label","GENERAL").param("audience","PUBLIC");
        switch(variant) {
            case "EMPTY" -> request.param("version","");
            case "REPEATED_EMPTY" -> request.param("version","","99");
            case "WHITESPACE" -> request.param("version"," ","0");
            default -> { }
        }
        if(variant.equals("WHITESPACE") || variant.equals("REPEATED_EMPTY")) {
            mvc.perform(request).andExpect(status().isBadRequest());assertThat(draftEffects()).isEqualTo(before);
        } else {
            mvc.perform(request).andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("select count(*) from workbench_receipt where command_id=?",Long.class,UUID.fromString(command))).isEqualTo(1);
        }
    }

    @Test void shouldKeepRepeatedStringClassificationJoinedAndBlankFirstCommandRejected() throws Exception {
        authenticateOperator();var author=author();var before=draftEffects();
        for(String field:List.of("label","audience","command")) {
            var request=multipart("/moderation/drafts").session(author).with(csrf()).param("text","classification negative caller text");
            request.param("command",field.equals("command")?new String[]{"",UUID.randomUUID().toString()}:new String[]{UUID.randomUUID().toString()});
            request.param("label",field.equals("label")?new String[]{"GENERAL","ADULT"}:new String[]{"GENERAL"});
            request.param("audience",field.equals("audience")?new String[]{"PUBLIC","PRIVATE"}:new String[]{"PUBLIC"});
            mvc.perform(request).andExpect(status().is4xxClientError());assertThat(draftEffects()).isEqualTo(before);
        }
    }

    private Map<String,Long> effectCounts() {
        var result=new TreeMap<String,Long>();
        for(String table:draftTables()) { result.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class)); }
        return result;
    }
    private List<String> draftTables() {
        return jdbc.queryForList("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'workbench_%' or tablename like 'moderation_%' or tablename like 'files_%') order by tablename",String.class);
    }
    private Map<String,List<String>> draftEffects() throws Exception {
        var result=new TreeMap<String,List<String>>();
        for(String table:draftTables()) {
            assertThat(table).matches("[a-z_]+");result.put(table,jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class));
        }
        var bytes=new TreeMap<String,String>();Path root=Path.of(".runtime/workbench-recovery-http-artifacts");
        if(Files.exists(root)) { try(var paths=Files.list(root)) { for(var file:paths.toList()) { bytes.put(file.getFileName().toString(),ContentDigest.bytes(Files.readAllBytes(file))); } } }
        result.put("artifactBytes",bytes.entrySet().stream().map(Object::toString).toList());return result;
    }

    private String totp(String secret) throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
        for(char c:secret.toCharArray()) {buffer=(buffer<<5)|alphabet.indexOf(c);bits+=5;if(bits>=8){bits-=8;decoded.write((buffer>>bits)&255);}}
        Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decoded.toByteArray(),"HmacSHA1"));byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond()/30).array());int offset=digest[digest.length-1]&15;
        int value=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;return String.format(Locale.ROOT,"%06d",value%1000000);
    }
}
