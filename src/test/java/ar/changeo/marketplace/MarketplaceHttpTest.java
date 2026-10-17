package ar.changeo.marketplace;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.*;
import ar.changeo.moderation.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes={ChangeoApplication.class,MarketplaceHttpTest.Configuration.class}, properties={"spring.flyway.schemas=profiles_http_test", "spring.flyway.default-schema=profiles_http_test", "spring.jpa.properties.hibernate.default_schema=profiles_http_test", "spring.datasource.hikari.schema=profiles_http_test", "changeo.mfa-key=.runtime/profiles-http-mfa.key", "changeo.files-root=.runtime/profiles-http-artifacts", "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true", "changeo.moderation-worker=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class MarketplaceHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoBean TokenDelivery delivery;
    Map<String,String> tokens=new ConcurrentHashMap<>();
    Properties enrollment;
    MockHttpSession operator;
    @TestConfiguration static class Configuration {
        @Bean @Primary MarketplacePostgresTest.TestClock httpClock() { return new MarketplacePostgresTest.TestClock(); }
    }
    @BeforeEach void setup() throws Exception {
        ((MarketplacePostgresTest.TestClock)clock).advance(61); jdbc.update("delete from moderation_task");
        reset(delivery); tokens.clear();
        when(delivery.deliver(any(),anyString(),anyString())).thenAnswer(invocation -> { tokens.put(invocation.getArgument(0)+":"+invocation.getArgument(1),invocation.getArgument(2)); return true; });
        Path path=Path.of(".runtime/p1-http-enrollment-"+UUID.randomUUID());
        try {
            operators.provision("p1httpoperator"+UUID.randomUUID()+"@example.test",Set.of("IDENTITY_ADMIN","RESTRICTION_ADMIN","MODERATION_REVIEWER","MODERATION_AUDITOR"),path);
            enrollment=new Properties(); try (var input=Files.newBufferedReader(path)) { enrollment.load(input); }
        } finally { Files.deleteIfExists(path); }
        operator=login(enrollment.getProperty("contact"),enrollment.getProperty("password"));
    }

    MockHttpSession login(String contact,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("username",contact).param("password",password)).andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
    }
    void operatorProof() throws Exception {
        mvc.perform(post("/moderation/operator/mfa").session(operator).with(csrf()).param("code",totp(enrollment.getProperty("totpBase32")))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/moderation/operator/reauth").session(operator).with(csrf()).param("password",enrollment.getProperty("password"))).andExpect(status().is3xxRedirection());
    }
    AccountFixture author(boolean provider) throws Exception {
        return author(provider,30);
    }
    AccountFixture author(boolean provider,Integer age) throws Exception {
        String contact="p1http"+UUID.randomUUID()+"@example.test",password="Synthetic-password-12345";
        UUID id=accounts.register(contact,password).accountId().value(); accounts.verifyContact(id,tokens.get(id+":CONTACT"));
        if (age!=null) { mvc.perform(post("/admin/accounts/"+id+"/eligibility").session(operator).with(csrf()).param("kind","AGE").param("age",age.toString())).andExpect(status().is3xxRedirection()); }
        if (provider) { mvc.perform(post("/admin/accounts/"+id+"/eligibility").session(operator).with(csrf()).param("kind","PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection()); }
        accounts.preferences(id,true,age!=null && age>=18); return new AccountFixture(id,login(contact,password));
    }
    UUID draft(AccountFixture author,String type,String title) throws Exception {
        return draft(author,PresenceType.valueOf(type),title,"GENERAL",null);
    }
    UUID current(UUID resource) { return jdbc.queryForObject("select current_revision from marketplace_resource where id=?",UUID.class,resource); }
    void submit(AccountFixture author,UUID resource) throws Exception {
        mvc.perform(prepare(author,resource,null)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/submit").session(author.session()).with(csrf()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
    }
    long version(UUID resource) { return jdbc.queryForObject("select version from marketplace_resource where id=?",Long.class,resource); }
    MockMultipartHttpServletRequestBuilder prepare(AccountFixture actor,UUID resource,MockMultipartFile upload) {
        var request=multipart("/marketplace/prepare");
        if (upload!=null) { request.file(upload); }
        request.session(actor.session()).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",current(resource).toString()).param("version",Long.toString(version(resource))).param("omitPreviousMedia",Boolean.toString(upload==null));
        return request;
    }
    UUID draft(AccountFixture actor,PresenceType type,String title,String label,MockMultipartFile upload) throws Exception {
        mvc.perform(post("/marketplace/compose").session(actor.session()).with(csrf()).param("type",type.name()).param("subject",actor.id().toString()).param("title",title).param("body","Restauración sintética de un instrumento inusual").param("tags","restauración,instrumento").param("label",label)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Paso 2 de 2")));
        var composition=(MarketplaceController.Composition)actor.session().getAttribute("marketplaceComposition");
        var save=multipart("/marketplace/drafts");
        if (upload!=null) { save.file(upload); }
        save.session(actor.session()).with(csrf()).param("stage",composition.stage().toString()).param("province","Buenos Aires").param("area","La Plata").param("modes","LOCAL","REMOTE","SHIPPED").param("availability","Semanas de prueba");
        String location=mvc.perform(save).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring(location.lastIndexOf('/')+1));
    }
    UUID reviewCase(UUID resource,PresenceType type) throws Exception {
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command",UUID.randomUUID().toString()).param("kind",type.surface().name()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=?",UUID.class,current(resource));
    }
    void approve(UUID resource,PresenceType type,String label) throws Exception {
        worker.processOne();
        UUID caseId=reviewCase(resource,type);
        mvc.perform(decision(caseId,label)).andExpect(status().is3xxRedirection());
        worker.processOne();
    }
    MockHttpServletRequestBuilder decision(UUID caseId,String label) {
        return post("/moderation/operator/action").session(operator).with(csrf()).param("command",UUID.randomUUID().toString()).param("caseId",caseId.toString()).param("version","0").param("action","APPROVE").param("label",label).param("reason","SAFETY");
    }
    FileFixture attachment(UUID resource,UUID revision) {
        return jdbc.queryForObject("select a.file_id,a.digest from marketplace_attachment a where a.revision_id=?",(row,index) -> new FileFixture(row.getObject(1,UUID.class),resource,revision,row.getString(2)),revision);
    }
    MockHttpServletRequestBuilder file(FileFixture file) {
        return get("/marketplace/files/"+file.id()).param("resource",file.resource().toString()).param("revision",file.revision().toString()).param("digest",file.digest());
    }
    String route(UUID resource,PresenceType type) { return (type.profile() ? "/profiles/" : "/listings/")+resource; }
    MockHttpServletRequestBuilder transition(AccountFixture actor,UUID resource,long version,UUID command,String action) {
        return post("/marketplace/state").session(actor.session()).with(csrf()).param("command",command.toString()).param("resource",resource.toString()).param("version",Long.toString(version)).param("action",action);
    }

    @Test void shouldOfferAnonymousSeparateDiscoveryWithTruthfulEmptyState() throws Exception {
        mvc.perform(get("/marketplace").param("type", "OFFER").param("q","no-existe-"+UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ofertas")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No encontramos publicaciones")));
    }

    @Test void shouldUseActualSessionsCsrfStagedFormsAndExactPublicProjections() throws Exception {
        operatorProof(); var author=author(true); String title="Oferta <script>impresión</script> "+UUID.randomUUID();
        mvc.perform(get("/profiles/create")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/marketplace/create").session(author.session()).param("type","OFFER")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Paso 1 de 2")));
        UUID resource=draft(author,"OFFER",title);
        mvc.perform(get("/marketplace/manage/"+resource).session(author.session())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Borrador guardado"))).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
        for (String suffix:List.of("","/preview","/share")) { mvc.perform(get("/listings/"+resource+suffix)).andExpect(status().isForbidden()); }
        submit(author,resource); worker.processOne();
        mvc.perform(get("/moderation/evidence").session(operator).param("kind","OFFER_BODY").param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
        mvc.perform(post("/moderation/operator/case").session(operator).with(csrf()).param("command",UUID.randomUUID().toString()).param("kind","OFFER_BODY").param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
        UUID caseId=jdbc.queryForObject("select c.id from moderation_case c join moderation_snapshot p on p.id=c.submission_id where p.revision_id=?",UUID.class,current(resource));
        mvc.perform(post("/moderation/operator/action").session(operator).with(csrf()).param("command",UUID.randomUUID().toString()).param("caseId",caseId.toString()).param("version","0").param("action","APPROVE").param("label","GENERAL").param("reason","SAFETY")).andExpect(status().is3xxRedirection());
        worker.processOne();
        mvc.perform(get("/listings/"+resource)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))));
        for (String suffix:List.of("/preview","/share")) { mvc.perform(get("/listings/"+resource+suffix)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("impresión"))); }
        mvc.perform(post("/marketplace/state").session(author.session()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("version","0").param("action","CLOSE")).andExpect(status().isForbidden());
        mvc.perform(get("/marketplace").param("q","impresión")).andExpect(status().isOk());
        mvc.perform(get("/marketplace").param("page","-1")).andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.containsString("Filtros inválidos")));
    }

    @Test void shouldRejectForgedSubjectAndKeepPrivateDraftFromAnotherActualPrincipal() throws Exception {
        operatorProof(); var author=author(false); var other=author(false); UUID resource=draft(author,"REQUEST","Necesidad privada");
        mvc.perform(get("/marketplace/manage/"+resource).session(other.session())).andExpect(status().isForbidden());
        mvc.perform(post("/marketplace/compose").session(other.session()).with(csrf()).param("type","REQUEST").param("subject",author.id().toString()).param("actor",author.id().toString()).param("title","Actor falso").param("body","Intento sintético").param("label","GENERAL")).andExpect(status().isOk());
        var composition=(MarketplaceController.Composition)other.session().getAttribute("marketplaceComposition");
        mvc.perform(multipart("/marketplace/drafts").session(other.session()).with(csrf()).param("stage",composition.stage().toString()).param("province","Buenos Aires").param("area","La Plata").param("modes","REMOTE")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from marketplace_revision where title='Actor falso'",Long.class)).isZero();
    }

    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldRetainMinorDraftAndPublishFreshGuardianRevisionWithCurrentAuthority(PresenceType type) throws Exception {
        operatorProof(); var minor=author(type.provider(),16); var guardian=author(false);
        mvc.perform(post("/admin/accounts/"+minor.id()+"/eligibility").session(operator).with(csrf()).param("kind","MINOR_DRAFT")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/account/guardian/request").session(minor.session()).with(csrf()).param("other",guardian.id().toString()).param("actingAsGuardian","false")).andExpect(status().is3xxRedirection());
        UUID link=jdbc.queryForObject("select id from identity_guardian_link where minor_id=?",UUID.class,minor.id());
        mvc.perform(post("/admin/guardian/"+link+"/review").session(operator).with(csrf()).param("version","0").param("verified","true")).andExpect(status().is3xxRedirection());
        byte[] bytes="Portafolio sintético del menor".getBytes(StandardCharsets.UTF_8);
        var upload=new MockMultipartFile("uploads","portfolio.txt","text/plain",bytes);
        UUID resource=draft(minor,type,"Borrador de menor "+UUID.randomUUID(),"GENERAL",upload),original=current(resource);
        FileFixture originalFile=attachment(resource,original);
        mvc.perform(prepare(minor,resource,upload)).andExpect(status().isForbidden());
        mvc.perform(post("/marketplace/submit").session(minor.session()).with(csrf()).param("resource",resource.toString()).param("revision",original.toString())).andExpect(status().isForbidden());
        mvc.perform(prepare(guardian,resource,upload)).andExpect(status().is3xxRedirection());
        UUID published=current(resource); FileFixture publishedFile=attachment(resource,published);
        assertThat(published).isNotEqualTo(original);
        assertThat(publishedFile.id()).isNotEqualTo(originalFile.id());
        assertThat(publishedFile.digest()).isEqualTo(originalFile.digest());
        assertThat(jdbc.queryForObject("select actor_id from marketplace_revision where id=?",UUID.class,original)).isEqualTo(minor.id());
        assertThat(jdbc.queryForObject("select capability from marketplace_revision where id=?",String.class,original)).isEqualTo(type.draftCapability().name());
        assertThat(jdbc.queryForObject("select actor_id from marketplace_revision where id=?",UUID.class,published)).isEqualTo(guardian.id());
        assertThat(jdbc.queryForObject("select capability from marketplace_revision where id=?",String.class,published)).isEqualTo(type.publishCapability().name());
        mvc.perform(post("/marketplace/submit").session(guardian.session()).with(csrf()).param("resource",resource.toString()).param("revision",published.toString())).andExpect(status().is3xxRedirection());
        approve(resource,type,"GENERAL");
        mvc.perform(get(route(resource,type))).andExpect(status().isOk());
        mvc.perform(file(publishedFile)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(file(originalFile).session(minor.session())).andExpect(status().isForbidden());
        mvc.perform(transition(guardian,resource,version(resource),UUID.randomUUID(),"PAUSE")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/account/guardian/"+link+"/revoke").session(guardian.session()).with(csrf()).param("version","1")).andExpect(status().is3xxRedirection());
        long retainedVersion=version(resource);
        mvc.perform(prepare(guardian,resource,upload)).andExpect(status().isForbidden());
        mvc.perform(post("/marketplace/submit").session(guardian.session()).with(csrf()).param("resource",resource.toString()).param("revision",published.toString())).andExpect(status().isForbidden());
        for (String action:List.of("RESUME","CLOSE")) { mvc.perform(transition(guardian,resource,retainedVersion,UUID.randomUUID(),action)).andExpect(status().isForbidden()); }
        assertThat(current(resource)).isEqualTo(published); assertThat(version(resource)).isEqualTo(retainedVersion);
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot where revision_id=?",Long.class,original)).isZero();
        mvc.perform(get("/marketplace/manage/"+resource).session(minor.session())).andExpect(status().isOk());
        mvc.perform(file(publishedFile)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @CsvSource({"OFFER,SENSITIVE","PROVIDER_PROFILE,SENSITIVE","REQUEST,ADULT","CUSTOMER_PROFILE,ADULT"})
    void shouldRecheckAudienceOnEveryPublicProjectionAndExactFile(PresenceType type,String label) throws Exception {
        operatorProof(); var author=author(type.provider()); var viewer=author(false);
        String title="Audiencia "+UUID.randomUUID(); byte[] bytes="Muestra sintética de audiencia".getBytes(StandardCharsets.UTF_8);
        var upload=new MockMultipartFile("uploads","sample.txt","text/plain",bytes);
        UUID resource=draft(author,type,title,label,upload);
        mvc.perform(prepare(author,resource,upload)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/submit").session(author.session()).with(csrf()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
        approve(resource,type,label); FileFixture approvedFile=attachment(resource,current(resource));
        for (String suffix:List.of("","/preview","/share")) { mvc.perform(get(route(resource,type)+suffix).session(viewer.session())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(title))); }
        mvc.perform(file(approvedFile).session(viewer.session())).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(post("/account/preferences").session(viewer.session()).with(csrf())).andExpect(status().is3xxRedirection());
        for (String suffix:List.of("","/preview","/share")) { mvc.perform(get(route(resource,type)+suffix).session(viewer.session())).andExpect(status().isForbidden()); }
        mvc.perform(file(approvedFile).session(viewer.session())).andExpect(status().isForbidden());
        if (type.profile()) {
            mvc.perform(get("/profiles/account/"+author.id()+"/"+type).session(viewer.session())).andExpect(status().isForbidden());
        } else {
            mvc.perform(get("/marketplace").session(viewer.session()).param("type",type.name()).param("q",title)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/listings/"+resource))));
        }
        mvc.perform(post("/account/preferences").session(viewer.session()).with(csrf()).param("sensitive","true").param("adult","true")).andExpect(status().is3xxRedirection());
        mvc.perform(get(route(resource,type)+"/share").session(viewer.session())).andExpect(status().isOk());
        if (label.equals("ADULT")) {
            var minor=author(false,16); var unknown=author(false,null);
            for (AccountFixture denied:List.of(minor,unknown)) {
                for (String suffix:List.of("","/preview","/share")) { mvc.perform(get(route(resource,type)+suffix).session(denied.session())).andExpect(status().isForbidden()); }
                mvc.perform(file(approvedFile).session(denied.session())).andExpect(status().isForbidden());
            }
            mvc.perform(get(route(resource,type))).andExpect(status().isForbidden()); mvc.perform(file(approvedFile)).andExpect(status().isForbidden());
        } else {
            mvc.perform(get(route(resource,type))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Advertencia")));
        }
    }

    @Test void shouldAllowCustomerPublicationWithoutProviderPermissionAndDenyExpiredProviderCommands() throws Exception {
        operatorProof(); var customer=author(false);
        for (PresenceType type:List.of(PresenceType.CUSTOMER_PROFILE,PresenceType.REQUEST)) {
            UUID resource=draft(customer,type,"Cliente "+UUID.randomUUID(),"GENERAL",null); submit(customer,resource); approve(resource,type,"GENERAL");
            mvc.perform(get(route(resource,type))).andExpect(status().isOk());
        }
        for (PresenceType type:List.of(PresenceType.PROVIDER_PROFILE,PresenceType.OFFER)) {
            mvc.perform(post("/marketplace/compose").session(customer.session()).with(csrf()).param("type",type.name()).param("subject",customer.id().toString()).param("title","Proveedor sin permiso").param("body","Intento local").param("label","GENERAL")).andExpect(status().isOk());
            var stage=(MarketplaceController.Composition)customer.session().getAttribute("marketplaceComposition");
            mvc.perform(multipart("/marketplace/drafts").session(customer.session()).with(csrf()).param("stage",stage.stage().toString()).param("province","Buenos Aires").param("area","La Plata").param("modes","REMOTE")).andExpect(status().isForbidden());
        }
        var provider=author(true); UUID offer=draft(provider,"OFFER","Permiso temporal");
        UUID providerProfile=draft(provider,PresenceType.PROVIDER_PROFILE,"Perfil del proveedor "+UUID.randomUUID(),"GENERAL",null);
        submit(provider,providerProfile); approve(providerProfile,PresenceType.PROVIDER_PROFILE,"GENERAL");
        ((MarketplacePostgresTest.TestClock)clock).advance(86401); operatorProof();
        mvc.perform(post("/admin/accounts/"+provider.id()+"/eligibility").session(operator).with(csrf()).param("kind","AGE").param("age","30")).andExpect(status().is3xxRedirection());
        mvc.perform(prepare(provider,offer,null)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from moderation_snapshot p join moderation_reference r on r.id=p.reference_id where r.resource_id=?",Long.class,offer)).isZero();
        UUID customerProfile=draft(provider,PresenceType.CUSTOMER_PROFILE,"Perfil del mismo cliente "+UUID.randomUUID(),"GENERAL",null);
        submit(provider,customerProfile); approve(customerProfile,PresenceType.CUSTOMER_PROFILE,"GENERAL");
        assertThat(jdbc.queryForObject("select subject_id from marketplace_resource where id=?",UUID.class,customerProfile)).isEqualTo(provider.id());
        assertThat(jdbc.queryForObject("select subject_id from marketplace_resource where id=?",UUID.class,providerProfile)).isEqualTo(provider.id());
        mvc.perform(get("/profiles/account/"+provider.id()+"/CUSTOMER_PROFILE")).andExpect(status().isOk());
        mvc.perform(get("/profiles/account/"+provider.id()+"/PROVIDER_PROFILE")).andExpect(status().isOk());
        UUID request=draft(provider,"REQUEST","Cliente luego del vencimiento"); submit(provider,request); approve(request,PresenceType.REQUEST,"GENERAL");
        mvc.perform(get("/listings/"+request)).andExpect(status().isOk());
    }

    @ParameterizedTest @EnumSource(value=PresenceType.class,names={"PROVIDER_PROFILE","OFFER"})
    void shouldDenyActiveUploadAndQuarantinedBytesEvenToReviewer(PresenceType type) throws Exception {
        operatorProof(); var author=author(true);
        mvc.perform(post("/marketplace/compose").session(author.session()).with(csrf()).param("type",type.name()).param("subject",author.id().toString()).param("title","Archivo activo").param("body","Prueba local").param("label","GENERAL")).andExpect(status().isOk());
        var stage=(MarketplaceController.Composition)author.session().getAttribute("marketplaceComposition");
        long before=jdbc.queryForObject("select count(*) from files_artifact",Long.class);
        mvc.perform(multipart("/marketplace/drafts").file(new MockMultipartFile("uploads","active.html","text/html","<script>active</script>".getBytes(StandardCharsets.UTF_8))).session(author.session()).with(csrf()).param("stage",stage.stage().toString()).param("province","Buenos Aires").param("area","La Plata").param("modes","REMOTE")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from files_artifact",Long.class)).isEqualTo(before);
        var pdf=new MockMultipartFile("uploads","quarantine.pdf","application/pdf","%PDF-synthetic-uninspectable".getBytes(StandardCharsets.UTF_8));
        UUID resource=draft(author,type,"Cuarentena "+UUID.randomUUID(),"GENERAL",pdf);
        mvc.perform(prepare(author,resource,pdf)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/submit").session(author.session()).with(csrf()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
        worker.processOne(); FileFixture artifact=attachment(resource,current(resource));
        mvc.perform(file(artifact).session(author.session())).andExpect(status().isForbidden());
        mvc.perform(get("/moderation/files/"+artifact.id()).session(operator).param("resource",resource.toString()).param("revision",artifact.revision().toString()).param("kind",type.surface().name()).param("digest",artifact.digest())).andExpect(status().isForbidden());
        mvc.perform(decision(reviewCase(resource,type),"GENERAL")).andExpect(status().isForbidden());
        for (String suffix:List.of("","/preview","/share")) { mvc.perform(get(route(resource,type)+suffix)).andExpect(status().isForbidden()); }
        assertThat(jdbc.queryForObject("select approved_revision from marketplace_resource where id=?",UUID.class,resource)).isNull();
    }

    @Test void shouldKeepWholePendingRevisionPrivateAndWithdrawAllRoutesOnLifecycleChanges() throws Exception {
        operatorProof(); var author=author(true); var other=author(true);
        byte[] bytes="Portafolio exacto aprobado".getBytes(StandardCharsets.UTF_8);
        var upload=new MockMultipartFile("uploads","portfolio.txt","text/plain",bytes);
        String approvedTitle="Oferta aprobada "+UUID.randomUUID();
        UUID resource=draft(author,PresenceType.OFFER,approvedTitle,"GENERAL",upload),original=current(resource);
        mvc.perform(prepare(author,resource,upload)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/submit").session(author.session()).with(csrf()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
        approve(resource,PresenceType.OFFER,"GENERAL"); UUID approved=current(resource); FileFixture approvedFile=attachment(resource,approved);
        String projection=mvc.perform(get("/listings/"+resource+"/preview")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(file(approvedFile)).andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Content-Disposition","attachment; filename=\"portafolio-aprobado\""));
        mvc.perform(file(new FileFixture(approvedFile.id(),resource,approved,"0".repeat(64)))).andExpect(status().isForbidden());
        mvc.perform(file(new FileFixture(approvedFile.id(),resource,original,approvedFile.digest()))).andExpect(status().isForbidden());
        String pendingTitle="Campo privado "+UUID.randomUUID(); long editVersion=version(resource);
        mvc.perform(post("/marketplace/compose").session(author.session()).with(csrf()).param("type","OFFER").param("subject",author.id().toString()).param("resource",resource.toString()).param("version",Long.toString(editVersion)).param("title",pendingTitle).param("body","Descripción pendiente secreta").param("tags","privado").param("label","ADULT")).andExpect(status().isOk());
        var stage=(MarketplaceController.Composition)author.session().getAttribute("marketplaceComposition");
        mvc.perform(multipart("/marketplace/drafts").file(new MockMultipartFile("uploads","pending.txt","text/plain","Archivo pendiente secreto".getBytes(StandardCharsets.UTF_8))).session(author.session()).with(csrf()).param("stage",stage.stage().toString()).param("province","Córdoba").param("area","Villa María").param("modes","SHIPPED").param("availability","Disponibilidad privada").param("amount","123.45").param("deadline","2027-01-15")).andExpect(status().is3xxRedirection());
        FileFixture pendingFile=attachment(resource,current(resource));
        for (String suffix:List.of("/preview","/share")) { mvc.perform(get("/listings/"+resource+suffix)).andExpect(status().isOk()).andExpect(content().string(projection)); }
        mvc.perform(get("/listings/"+resource)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secreto")))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(pendingTitle))));
        mvc.perform(file(pendingFile).session(author.session())).andExpect(status().isForbidden());
        mvc.perform(file(approvedFile)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(get("/marketplace").param("tag","privado").param("province","Córdoba").param("area","Villa María").param("mode","SHIPPED")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/listings/"+resource))));
        mvc.perform(get("/marketplace").param("q",approvedTitle).param("tag","restauración").param("province","Buenos Aires").param("area","La Plata").param("mode","LOCAL")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/listings/"+resource)));
        mvc.perform(transition(author,resource,editVersion,UUID.randomUUID(),"CLOSE")).andExpect(status().isForbidden());
        mvc.perform(transition(other,resource,version(resource),UUID.randomUUID(),"CLOSE")).andExpect(status().isForbidden());
        UUID pause=UUID.randomUUID(); long pauseVersion=version(resource);
        mvc.perform(transition(author,resource,pauseVersion,pause,"PAUSE")).andExpect(status().is3xxRedirection());
        mvc.perform(transition(author,resource,pauseVersion,pause,"PAUSE")).andExpect(status().is3xxRedirection());
        assertThat(version(resource)).isEqualTo(pauseVersion+1);
        for (String suffix:List.of("","/preview","/share")) { mvc.perform(get("/listings/"+resource+suffix)).andExpect(status().isForbidden()); }
        mvc.perform(file(approvedFile)).andExpect(status().isForbidden());
        mvc.perform(transition(author,resource,version(resource),UUID.randomUUID(),"RESUME")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/listings/"+resource+"/share")).andExpect(status().isOk()).andExpect(content().string(projection));
        mvc.perform(transition(author,resource,version(resource),UUID.randomUUID(),"CLOSE")).andExpect(status().is3xxRedirection());
        mvc.perform(transition(author,resource,version(resource),UUID.randomUUID(),"RESUME")).andExpect(status().isForbidden());
        mvc.perform(transition(author,resource,pauseVersion,pause,"PAUSE")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select lifecycle from marketplace_resource where id=?",String.class,resource)).isEqualTo("CLOSED");
        mvc.perform(get("/marketplace/manage/"+resource).session(author.session())).andExpect(status().isOk());
        mvc.perform(file(approvedFile)).andExpect(status().isForbidden());
    }

    String totp(String secret) throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream(); int buffer=0,bits=0;
        for (char c:secret.toCharArray()) { buffer=(buffer<<5)|alphabet.indexOf(c); bits+=5; if(bits>=8) { bits-=8; decoded.write((buffer>>bits)&255); } }
        Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(decoded.toByteArray(),"HmacSHA1")); byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond()/30).array());
        return String.format(Locale.ROOT,"%06d",(ByteBuffer.wrap(digest,digest[digest.length-1]&15,4).getInt()&0x7fffffff)%1000000);
    }
    record AccountFixture(UUID id,MockHttpSession session) {}
    record FileFixture(UUID id,UUID resource,UUID revision,String digest) {}
}
