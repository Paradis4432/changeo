package ar.changeo.marketplace;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import ar.changeo.identity.OperatorService;
import ar.changeo.identity.TokenDelivery;
import ar.changeo.moderation.ModerationWorker;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes={ChangeoApplication.class,MarketplaceHttpTest.Configuration.class}, properties={"spring.flyway.schemas=upload_recovery_test", "spring.flyway.default-schema=upload_recovery_test", "spring.jpa.properties.hibernate.default_schema=upload_recovery_test", "spring.datasource.hikari.schema=upload_recovery_test", "changeo.mfa-key=.runtime/upload-recovery-mfa.key", "changeo.files-root=.runtime/upload-recovery-artifacts", "changeo.moderation-worker=false", "changeo.synthetic-evidence=true", "changeo.synthetic-moderation=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class MarketplaceUploadRecoveryHttpTest {
    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired OperatorService operators;
    @Autowired ModerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoBean TokenDelivery delivery;
    MarketplaceHttpTest fixtures;
    MarketplaceHttpTest.AccountFixture actor;
    MockHttpSession session;

    @BeforeEach void login() throws Exception {
        fixtures=new MarketplaceHttpTest();
        fixtures.mvc=mvc; fixtures.accounts=accounts; fixtures.operators=operators; fixtures.worker=worker;
        fixtures.jdbc=jdbc; fixtures.clock=clock; fixtures.delivery=delivery;
        fixtures.setup(); fixtures.operatorProof(); actor=fixtures.author(true); session=actor.session();
    }

    @ParameterizedTest @EnumSource(PresenceType.class)
    void shouldRecoverOnlyPreviouslyStagedValuesWithoutChangingStageOrWriting(PresenceType type) throws Exception {
        compose(type);
        var initial=composition();
        mvc.perform(multipart("/marketplace/back").session(session).with(csrf()).param("stage",initial.stage().toString())
                .param("province","Córdoba").param("area","Villa María").param("modes","REMOTE","SHIPPED")
                .param("availability","Tardes").param("amount",type.profile() ? "" : "123.45")
                .param("deadline",type.profile() ? "" : "2027-01-15").param("badgeOptIn",type.profile() ? "true" : "false"))
                .andExpect(status().isOk());
        compose(type,composition().stage());
        var saved=composition();
        var counts=counts();
        String html=HtmlUtils.htmlUnescape(mvc.perform(get("/marketplace/recover").session(session).param("stage",saved.stage().toString()))
                .andExpect(status().isOk()).andExpect(view().name("marketplace-coverage"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertThat(composition()).isSameAs(saved);
        assertThat(html).contains("Villa María","Tardes","archivos","no pudimos leer","Descripción conservada");
        assertThat(html).doesNotContain("value=\"Archivo rechazado\"");
        assertThat(html).contains("/marketplace/drafts?recovery="+saved.stage(),"/marketplace/back?recovery="+saved.stage());
        assertThat(counts()).isEqualTo(counts);
        var save=multipart("/marketplace/drafts?recovery="+UUID.randomUUID()).session(session).with(csrf())
                .param("stage",saved.stage().toString()).param("province","Córdoba").param("area","Villa María")
                .param("modes","REMOTE","SHIPPED").file(new MockMultipartFile("uploads","fresh.txt","text/plain","Nuevo archivo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String location=mvc.perform(save).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        UUID resource=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertThat(counts()).containsExactly(counts.get(0)+1,counts.get(1)+1,counts.get(2)+1,counts.get(3)+1);
        assertThat(jdbc.queryForObject("select type from marketplace_resource where id=?",String.class,resource)).isEqualTo(type.name());
        assertThat(jdbc.queryForObject("select subject_id from marketplace_resource where id=?",UUID.class,resource)).isEqualTo(actor.id());
        assertThat(session.getAttribute("marketplaceComposition")).isNull();
        mvc.perform(get("/marketplace/recover").session(session).param("stage",saved.stage().toString())).andExpect(status().isForbidden());
    }

    @Test void shouldRecoverDefaultsTruthfullyAndRejectObsoleteStagesAndWrongSteps() throws Exception {
        compose(PresenceType.REQUEST);
        var original=composition();
        String html=mvc.perform(get("/marketplace/recover").session(session).param("stage",original.stage().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("valores anteriores de esta sesión o los valores iniciales","seleccionar los archivos");
        mvc.perform(get("/marketplace/create").session(session).param("type","OFFER")).andExpect(status().isOk());
        mvc.perform(get("/marketplace/recover").session(session).param("stage",original.stage().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/marketplace/recover").session(session).param("stage",composition().stage().toString())).andExpect(status().isForbidden());
    }

    @Test void shouldRejectMissingForeignAndCrossActorCompositionsWithoutWrites() throws Exception {
        var before=counts();
        mvc.perform(get("/marketplace/recover").session(session).param("stage",UUID.randomUUID().toString())).andExpect(status().isForbidden());
        compose(PresenceType.REQUEST);
        var saved=composition();
        var other=fixtures.author(false);
        mvc.perform(get("/marketplace/recover").session(other.session()).param("stage",saved.stage().toString())).andExpect(status().isForbidden());
        String contact=jdbc.queryForObject("select contact from identity_account where id=?",String.class,other.id());
        session=(MockHttpSession)mvc.perform(post("/login").session(session).with(csrf()).param("username",contact)
                .param("password","Synthetic-password-12345")).andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
        assertThat(composition()).isSameAs(saved);
        mvc.perform(get("/marketplace/recover").session(session).param("stage",saved.stage().toString())).andExpect(status().isForbidden());
        mvc.perform(multipart("/marketplace/drafts").session(session).with(csrf()).param("stage",saved.stage().toString())
                .param("province","Buenos Aires").param("area","Zona").param("modes","REMOTE")).andExpect(status().isForbidden());
        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"https://invalid.example/","1-1-1-1-1","not-a-stage",""})
    void shouldRejectMalformedRecoveryStages(String stage) throws Exception {
        compose(PresenceType.REQUEST);
        mvc.perform(get("/marketplace/recover").session(session).param("stage",stage)).andExpect(status().isBadRequest());
    }

    @Test void shouldRejectDuplicateStageAndPreserveAuthenticationCsrfAndSessionEpoch() throws Exception {
        compose(PresenceType.REQUEST);
        String stage=composition().stage().toString();
        var before=counts();
        mvc.perform(get("/marketplace/recover").session(session).param("stage",stage,stage)).andExpect(status().isBadRequest());
        mvc.perform(get("/marketplace/recover").param("stage",stage)).andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/marketplace/drafts?recovery="+stage).session(session).param("stage",stage)
                .param("province","Buenos Aires").param("area","Zona").param("modes","REMOTE")).andExpect(status().isForbidden());
        assertThat(counts()).isEqualTo(before);
        jdbc.update("update identity_account set security_epoch=security_epoch+1 where id=?",actor.id());
        mvc.perform(get("/marketplace/recover").session(session).param("stage",stage)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"version","lifecycle","access"})
    void shouldReobserveEditedResourceBeforeRecovery(String change) throws Exception {
        UUID resource=fixtures.draft(actor,"REQUEST","Original conservado");
        mvc.perform(get("/marketplace/manage/"+resource+"/edit").session(session)).andExpect(status().isOk());
        compose(PresenceType.REQUEST,composition().stage());
        var saved=composition();
        var before=counts();
        if (change.equals("version")) { jdbc.update("update marketplace_resource set version=version+1 where id=?",resource); }
        else if (change.equals("lifecycle")) { mvc.perform(fixtures.transition(actor,resource,fixtures.version(resource),UUID.randomUUID(),"CLOSE")).andExpect(status().is3xxRedirection()); }
        else { accounts.preferences(actor.id(),false,false); }
        var after=counts();
        mvc.perform(get("/marketplace/recover").session(session).param("stage",saved.stage().toString())).andExpect(status().isForbidden());
        assertThat(composition()).isSameAs(saved);
        assertThat(counts()).isEqualTo(after);
        assertThat(jdbc.queryForObject("select title from marketplace_revision where id=?",String.class,fixtures.current(resource))).isEqualTo("Original conservado");
        assertThat(after.get(0)).isEqualTo(before.get(0));
    }

    @Test void shouldIgnoreRecoveryCarrierAsPostAuthorityAndKeepUnsafeUploadsDenied() throws Exception {
        compose(PresenceType.REQUEST);
        var saved=composition();
        var before=counts();
        mvc.perform(multipart("/marketplace/drafts?recovery="+saved.stage()).session(session).with(csrf())
                .param("stage",UUID.randomUUID().toString()).param("province","Buenos Aires").param("area","Zona").param("modes","REMOTE"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/marketplace/drafts?recovery="+saved.stage()).session(session).with(csrf())
                .param("stage",saved.stage().toString()).param("province","Buenos Aires").param("area","Zona").param("modes","REMOTE")
                .file(new MockMultipartFile("uploads","unsafe.html","text/html","<script>bad</script>".getBytes())))
                .andExpect(status().isForbidden());
        assertThat(counts()).isEqualTo(before);
    }

    private void compose(PresenceType type) throws Exception { compose(type,null); }
    private void compose(PresenceType type,UUID stage) throws Exception {
        var request=post("/marketplace/compose").session(session).with(csrf()).param("type",type.name()).param("subject",actor.id().toString())
                .param("title","Descripción conservada").param("body","Texto anterior privado").param("label","GENERAL");
        if (stage!=null) { request.param("stage",stage.toString()); }
        mvc.perform(request).andExpect(status().isOk());
    }
    private MarketplaceController.Composition composition() { return (MarketplaceController.Composition)session.getAttribute("marketplaceComposition"); }
    private List<Long> counts() {
        return List.of("marketplace_resource","marketplace_revision","marketplace_receipt","files_artifact").stream()
                .map(table -> jdbc.queryForObject("select count(*) from "+table,Long.class)).toList();
    }
}
