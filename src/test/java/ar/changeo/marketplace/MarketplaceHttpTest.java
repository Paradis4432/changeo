package ar.changeo.marketplace;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.*;
import ar.changeo.moderation.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
    @Autowired MarketplaceService service;
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
        String contact="p1http"+UUID.randomUUID()+"@example.test",password="Synthetic-password-12345";
        UUID id=accounts.register(contact,password).accountId().value(); accounts.verifyContact(id,tokens.get(id+":CONTACT"));
        mvc.perform(post("/admin/accounts/"+id+"/eligibility").session(operator).with(csrf()).param("kind","AGE").param("age","30")).andExpect(status().is3xxRedirection());
        if (provider) { mvc.perform(post("/admin/accounts/"+id+"/eligibility").session(operator).with(csrf()).param("kind","PROVIDER_ELIGIBILITY")).andExpect(status().is3xxRedirection()); }
        accounts.preferences(id,true,true); return new AccountFixture(id,login(contact,password));
    }
    UUID draft(AccountFixture author,String type,String title) throws Exception {
        mvc.perform(post("/marketplace/compose").session(author.session()).with(csrf()).param("type",type).param("subject",author.id().toString()).param("title",title).param("body","Restauración sintética de un instrumento inusual").param("tags","restauración,instrumento").param("label","GENERAL"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Paso 2 de 2")));
        var composition=(MarketplaceController.Composition)author.session().getAttribute("marketplaceComposition");
        String location=mvc.perform(multipart("/marketplace/drafts").session(author.session()).with(csrf()).param("stage",composition.stage().toString()).param("province","Buenos Aires").param("area","La Plata").param("modes","LOCAL","REMOTE","SHIPPED").param("availability","Semanas de prueba"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring(location.lastIndexOf('/')+1));
    }
    UUID current(UUID resource) { return jdbc.queryForObject("select current_revision from marketplace_resource where id=?",UUID.class,resource); }
    void submit(AccountFixture author,UUID resource) throws Exception {
        long version=jdbc.queryForObject("select version from marketplace_resource where id=?",Long.class,resource);
        mvc.perform(multipart("/marketplace/prepare").session(author.session()).with(csrf()).param("command",UUID.randomUUID().toString()).param("resource",resource.toString()).param("revision",current(resource).toString()).param("version",Long.toString(version)).param("omitPreviousMedia","true")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/submit").session(author.session()).with(csrf()).param("resource",resource.toString()).param("revision",current(resource).toString())).andExpect(status().is3xxRedirection());
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

    String totp(String secret) throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream(); int buffer=0,bits=0;
        for (char c:secret.toCharArray()) { buffer=(buffer<<5)|alphabet.indexOf(c); bits+=5; if(bits>=8) { bits-=8; decoded.write((buffer>>bits)&255); } }
        Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(decoded.toByteArray(),"HmacSHA1")); byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(clock.instant().getEpochSecond()/30).array());
        return String.format(Locale.ROOT,"%06d",(ByteBuffer.wrap(digest,digest[digest.length-1]&15,4).getInt()&0x7fffffff)%1000000);
    }
    record AccountFixture(UUID id,MockHttpSession session) {}
}
