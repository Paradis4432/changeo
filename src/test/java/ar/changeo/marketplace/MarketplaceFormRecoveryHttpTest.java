package ar.changeo.marketplace;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import ar.changeo.identity.TokenDelivery;
import ar.changeo.identity.OperatorService;
import ar.changeo.moderation.ModerationWorker;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.util.HtmlUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes={ChangeoApplication.class,MarketplaceHttpTest.Configuration.class}, properties={"spring.flyway.schemas=form_recovery_test", "spring.flyway.default-schema=form_recovery_test", "spring.jpa.properties.hibernate.default_schema=form_recovery_test", "spring.datasource.hikari.schema=form_recovery_test", "changeo.mfa-key=.runtime/form-recovery-mfa.key", "changeo.files-root=.runtime/form-recovery-artifacts", "changeo.moderation-worker=false", "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class MarketplaceFormRecoveryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @MockitoBean TokenDelivery delivery;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    MarketplaceHttpTest fixtures;
    UUID account;
    MockHttpSession session;
    MarketplaceHttpTest.AccountFixture actor;

    @BeforeEach void login() throws Exception {
        fixtures=new MarketplaceHttpTest();
        fixtures.mvc=mvc; fixtures.accounts=accounts; fixtures.operators=operators; fixtures.worker=worker;
        fixtures.jdbc=jdbc; fixtures.clock=clock; fixtures.delivery=delivery;
        fixtures.setup(); fixtures.operatorProof(); actor=fixtures.author(true);
        account=actor.id(); session=actor.session();
    }

    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldKeepCoverageEditableWhenModesAreMissing(PresenceType type) throws Exception {
        mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("type",type.name()).param("subject",account.toString())
                .param("title","Descripción sintética").param("body","Texto conservado").param("label","GENERAL")).andExpect(status().isOk());
        var composition=(MarketplaceController.Composition)session.getAttribute("marketplaceComposition");
        String html=HtmlUtils.htmlUnescape(mvc.perform(multipart("/marketplace/drafts").session(session).with(csrf()).param("stage",composition.stage().toString())
                .param("province","Córdoba").param("area","Villa María").param("availability","Por la tarde"))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-coverage")).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("Elegí al menos una modalidad", "Villa María", "Por la tarde", "Volver a la descripción", "seleccionar los archivos");
    }
    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldReturnDescriptionErrorsWithoutSaving(PresenceType type) throws Exception {
        long before=count("marketplace_resource");
        String html=HtmlUtils.htmlUnescape(mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("type",type.name())
                .param("subject",account.toString()).param("title","   ").param("body","Texto <privado> & conservado")
                .param("tags","uno,,dos").param("label","inventada"))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-compose")).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("Escribí un título", "hasta ocho etiquetas", "Elegí una clasificación", "Texto <privado> & conservado");
        assertThat(count("marketplace_resource")).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldPreserveRawCoverageThroughBackAndSaveExactlyOneRevision(PresenceType type) throws Exception {
        compose(type);
        UUID oldStage=stage();
        long resources=count("marketplace_resource"), revisions=count("marketplace_revision"), receipts=count("marketplace_receipt"), artifacts=count("files_artifact");
        var invalid=coverage(oldStage).param("area","   ").param("modes","REMOTE","SHIPPED");
        if (!type.profile()) { invalid.param("amount","12,xx").param("deadline","2027-02-30"); }
        else { invalid.param("badgeOptIn","true"); }
        invalid.file(file());
        String html=HtmlUtils.htmlUnescape(mvc.perform(invalid).andExpect(status().isOk()).andExpect(view().name("marketplace-coverage"))
                .andReturn().getResponse().getContentAsString());
        assertThat(html).contains("Escribí una zona amplia", "aria-invalid=\"true\"", "seleccionar los archivos");
        if (!type.profile()) { assertThat(html).contains("12,xx", "2027-02-30", "Ingresá un importe", "Ingresá una fecha válida"); }
        assertCounts(resources,revisions,receipts,artifacts);
        var back=coverage(oldStage).param("area","Zona escrita").param("modes","REMOTE","SHIPPED");
        back=back.with(request -> { request.setRequestURI("/marketplace/back"); return request; });
        if (!type.profile()) { back.param("amount","12,xx").param("deadline","2027-02-30"); }
        else { back.param("badgeOptIn","true"); }
        mvc.perform(back).andExpect(status().isOk()).andExpect(view().name("marketplace-compose"));
        assertThat(stage()).isNotEqualTo(oldStage);
        mvc.perform(coverage(oldStage).param("modes","REMOTE")).andExpect(status().isForbidden());
        UUID descriptionStage=stage();
        html=HtmlUtils.htmlUnescape(mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("stage",descriptionStage.toString())
                .param("type","REQUEST").param("subject",UUID.randomUUID().toString()).param("title","Título corregido")
                .param("body","Texto corregido <privado>").param("tags"," Dos , uno, DOS ").param("label","GENERAL"))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-coverage")).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("Zona escrita", "Por la tarde", "checked=\"checked\"");
        if (!type.profile()) { assertThat(html).contains("12,xx", "2027-02-30"); }
        else { assertThat(html).contains("name=\"badgeOptIn\" value=\"true\" checked=\"checked\""); }
        var composition=(MarketplaceController.Composition)session.getAttribute("marketplaceComposition");
        assertThat(composition.type()).isEqualTo(type); assertThat(composition.subject()).isEqualTo(account);
        mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("stage",descriptionStage.toString()).param("type",type.name())
                .param("subject",account.toString()).param("title","Obsoleto").param("body","Obsoleto").param("label","GENERAL")).andExpect(status().isForbidden());
        assertCounts(resources,revisions,receipts,artifacts);
        UUID saveStage=stage();
        var save=coverage(saveStage).param("area","La Plata").param("modes","REMOTE","SHIPPED").file(file());
        if (!type.profile()) { save.param("amount","123.45").param("deadline","2027-01-15"); }
        else { save.param("badgeOptIn","true"); }
        String location=mvc.perform(save).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertCounts(resources+1,revisions+1,receipts+1,artifacts+1);
        var details=jdbc.queryForMap("select title,body,tags,area,availability from marketplace_revision where id=?",fixtures.current(resource));
        assertThat(details.get("title")).isEqualTo("Título corregido"); assertThat(details.get("area")).isEqualTo("La Plata");
        assertThat(jdbc.queryForObject("select type from marketplace_resource where id=?",String.class,resource)).isEqualTo(type.name());
        assertThat(jdbc.queryForObject("select count(*) from marketplace_attachment where revision_id=?",Long.class,fixtures.current(resource))).isEqualTo(1);
        assertThat(session.getAttribute("marketplaceComposition")).isNull();
        mvc.perform(coverage(saveStage).param("modes","REMOTE")).andExpect(status().isForbidden());
        assertCounts(resources+1,revisions+1,receipts+1,artifacts+1);
    }

    @Test void shouldRejectMissingForeignUnknownAndCsrfStagesWithoutWrites() throws Exception {
        long before=count("marketplace_resource");
        mvc.perform(coverage(UUID.randomUUID()).param("modes","REMOTE")).andExpect(status().isForbidden());
        compose(PresenceType.REQUEST);
        UUID ownStage=stage();
        var other=fixtures.author(false);
        mvc.perform(multipart("/marketplace/drafts").session(other.session()).with(csrf()).param("stage",ownStage.toString())
                .param("province","Buenos Aires").param("area","Zona").param("modes","REMOTE")).andExpect(status().isForbidden());
        mvc.perform(multipart("/marketplace/drafts").session(session).param("stage",ownStage.toString()).param("modes","REMOTE")).andExpect(status().isForbidden());
        mvc.perform(coverage(ownStage).param("modes","UNKNOWN")).andExpect(status().isOk()).andExpect(view().name("marketplace-coverage"));
        mvc.perform(coverage(ownStage).param("modes","REMOTE").param("badgeOptIn","true")).andExpect(status().isOk()).andExpect(view().name("marketplace-coverage"));
        assertThat(count("marketplace_resource")).isEqualTo(before);
    }

    @Test void shouldRejectConcurrentResourceChangesOnBackContinueAndSave() throws Exception {
        UUID resource=fixtures.draft(actor,"REQUEST","Original sintético");
        long originalRevisions=count("marketplace_revision");
        mvc.perform(get("/marketplace/manage/"+resource+"/edit").session(session)).andExpect(status().isOk());
        UUID editStage=stage();
        mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("stage",editStage.toString()).param("type","REQUEST")
                .param("subject",account.toString()).param("title","Edición pendiente").param("body","Privado").param("label","GENERAL")).andExpect(status().isOk());
        UUID coverageStage=stage();
        mvc.perform(fixtures.transition(actor,resource,fixtures.version(resource),UUID.randomUUID(),"PAUSE")).andExpect(status().is3xxRedirection());
        mvc.perform(coverage(coverageStage).param("modes","REMOTE")).andExpect(status().isForbidden());
        mvc.perform(multipart("/marketplace/back").session(session).with(csrf()).param("stage",coverageStage.toString())).andExpect(status().isForbidden());
        assertThat(count("marketplace_revision")).isEqualTo(originalRevisions);
        resource=fixtures.draft(actor,"REQUEST","Segundo recurso sintético");
        originalRevisions=count("marketplace_revision");
        mvc.perform(get("/marketplace/manage/"+resource+"/edit").session(session)).andExpect(status().isOk());
        editStage=stage();
        mvc.perform(fixtures.transition(actor,resource,fixtures.version(resource),UUID.randomUUID(),"PAUSE")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("stage",editStage.toString()).param("type","REQUEST")
                .param("subject",account.toString()).param("title","Viejo").param("body","Viejo").param("label","GENERAL")).andExpect(status().isForbidden());
        assertThat(count("marketplace_revision")).isEqualTo(originalRevisions);
    }

    @Test void shouldRejectExcessAttachmentsBeforePersistenceAndKeepUnsafeUploadForbidden() throws Exception {
        compose(PresenceType.REQUEST);
        long resources=count("marketplace_resource"),revisions=count("marketplace_revision"),receipts=count("marketplace_receipt"),artifacts=count("files_artifact");
        var save=coverage(stage()).param("area","Zona").param("modes","REMOTE");
        for (int i=0;i<5;i++) { save.file(file()); }
        mvc.perform(save).andExpect(status().isOk()).andExpect(view().name("marketplace-coverage"));
        assertCounts(resources,revisions,receipts,artifacts);
        mvc.perform(coverage(stage()).param("area","Zona").param("modes","REMOTE").file(new MockMultipartFile("uploads","unsafe.html","text/html","<script>bad</script>".getBytes())))
                .andExpect(status().isForbidden());
        assertCounts(resources,revisions,receipts,artifacts);
    }

    @ParameterizedTest @ValueSource(strings={"1000000000000","1e2147483647","1e-2147483647","-1","0.001"})
    void shouldRejectAmountsOutsidePersistableRangeWithoutWriting(String amount) throws Exception {
        compose(PresenceType.REQUEST);
        long before=count("marketplace_revision");
        String html=HtmlUtils.htmlUnescape(mvc.perform(coverage(stage()).param("area","Zona").param("modes","REMOTE").param("amount",amount))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-coverage")).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("Ingresá un importe",amount); assertThat(count("marketplace_revision")).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"-999999999-01-01","-4712-01-01","0000-01-01","+999999999-12-31"})
    void shouldRejectDatesOutsideDatabaseRangeWithoutWriting(String deadline) throws Exception {
        compose(PresenceType.REQUEST);
        long before=count("marketplace_revision");
        String html=HtmlUtils.htmlUnescape(mvc.perform(coverage(stage()).param("area","Zona").param("modes","REMOTE").param("deadline",deadline))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-coverage")).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("fuera del rango permitido",deadline); assertThat(count("marketplace_revision")).isEqualTo(before);
    }

    @Test void shouldSaveMaximumPersistableAmount() throws Exception {
        compose(PresenceType.REQUEST);
        String location=mvc.perform(coverage(stage()).param("area","Zona").param("modes","REMOTE").param("amount","999999999999.99"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertThat(jdbc.queryForObject("select amount from marketplace_revision where id=?",java.math.BigDecimal.class,fixtures.current(resource)))
                .isEqualByComparingTo("999999999999.99");
    }

    private void compose(PresenceType type) throws Exception {
        mvc.perform(post("/marketplace/compose").session(session).with(csrf()).param("type",type.name()).param("subject",account.toString())
                .param("title","Descripción sintética").param("body","Texto conservado").param("label","GENERAL")).andExpect(status().isOk());
    }
    private UUID stage() { return ((MarketplaceController.Composition)session.getAttribute("marketplaceComposition")).stage(); }
    private MockMultipartHttpServletRequestBuilder coverage(UUID stage) {
        var request=multipart("/marketplace/drafts");
        request.session(session).with(csrf()).param("stage",stage.toString()).param("province","Buenos Aires").param("availability","Por la tarde");
        return request;
    }
    private MockMultipartFile file() { return new MockMultipartFile("uploads","portfolio.txt","text/plain","Muestra sintética".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }
    private void assertCounts(long resources,long revisions,long receipts,long artifacts) {
        assertThat(count("marketplace_resource")).isEqualTo(resources); assertThat(count("marketplace_revision")).isEqualTo(revisions);
        assertThat(count("marketplace_receipt")).isEqualTo(receipts); assertThat(count("files_artifact")).isEqualTo(artifacts);
    }

}
