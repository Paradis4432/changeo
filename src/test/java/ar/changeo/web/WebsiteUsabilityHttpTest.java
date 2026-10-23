package ar.changeo.web;

import ar.changeo.ChangeoApplication;
import ar.changeo.identity.AccountService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ChangeoApplication.class, properties = {
        "changeo.synthetic-evidence=false", "changeo.synthetic-moderation=false",
        "changeo.synthetic-payments=false", "changeo.synthetic-subscriptions=false",
        "changeo.moderation-worker=false", "changeo.payments-worker=false", "changeo.subscription-worker=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WebsiteUsabilityHttpTest {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "website_usability_" + RUN;
    private static final Path RUNTIME = Path.of(".runtime", "website-usability-20261017", "http-tests", RUN);

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;

    @DynamicPropertySource
    static void isolatedResources(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> requiredEnvironment("CHANGEO_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> requiredEnvironment("CHANGEO_DATABASE_USER"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("CHANGEO_DATABASE_PASSWORD"));
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("changeo.mfa-key", () -> RUNTIME.resolve("mfa.key").toString());
        properties.add("changeo.mailbox", () -> RUNTIME.resolve("mailbox").toString());
        properties.add("changeo.files-root", () -> RUNTIME.resolve("artifacts").toString());
    }

    @Test
    void shouldKeepAnonymousNavigationFocusedAndPrivateDestinationsGuarded() throws Exception {
        String home = page("/");
        String header = home.substring(home.indexOf("<header"), home.indexOf("</header>"));
        assertThat(link(header, "Changeo")).isEqualTo("/");
        assertThat(link(header, "Buscar")).isEqualTo("/marketplace");
        assertThat(link(header, "Ingresar")).isEqualTo("/login");
        assertThat(link(header, "Crear cuenta")).isEqualTo("/register");
        assertThat(header).doesNotContain("/logout", "/marketplace/mine", "/account", "/admin", "/moderation");
        assertThat(link(home, "Ayuda")).isEqualTo("/support");
        assertThat(link(home, "Derechos")).isEqualTo("/rights");
        mvc.perform(get("/marketplace/mine")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/account")).andExpect(status().is3xxRedirection());
    }

    @Test
    void shouldRenderAccountDestinationsAndRequireRenderedCsrfForLogout() throws Exception {
        MockHttpSession session = login();
        String home = mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String header = home.substring(home.indexOf("<header"), home.indexOf("</header>"));
        for (String destination : new String[]{"/marketplace", "/marketplace/mine", "/conversations", "/notifications",
                "/jobs/support", "/subscriptions", "/account", "/account/guardian", "/moderation", "/content", "/admin/mfa"}) {
            assertThat(header).contains("href=\"" + destination + "\"");
        }
        assertThat(home).doesNotContain("href=\"/login\"", "href=\"/register\"");
        assertThat(header).contains("<summary>Mi cuenta</summary>", "<summary>Pruebas y administración</summary>");
        String logout = element(header, "form", "action", "/logout");
        assertThat(attribute(logout, "method")).isEqualToIgnoringCase("post");
        String token = attribute(element(logout, "input", "name", "_csrf"), "value");
        assertThat(token).isNotBlank();
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/account").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).param("_csrf", token)).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(page("/")).doesNotContain("action=\"/logout\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFFER", "REQUEST"})
    void shouldPreserveEscapedFiltersAcrossTypeSwitchAndResetOnlySearch(String type) throws Exception {
        String text = "<servicio> & \"especial\"";
        String tag = "impresión&3d";
        String area = "Villa & María";
        String html = mvc.perform(get("/marketplace").param("type", type).param("q", text).param("tag", tag)
                        .param("province", "Córdoba").param("area", area).param("mode", "REMOTE").param("page", "3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String currentLabel = type.equals("OFFER") ? "Ver ofertas" : "Ver solicitudes";
        String otherLabel = type.equals("OFFER") ? "Ver solicitudes" : "Ver ofertas";
        String otherType = type.equals("OFFER") ? "REQUEST" : "OFFER";
        assertThat(attribute(linkElement(html, currentLabel), "aria-current")).isEqualTo("page");
        assertThat(linkElement(html, otherLabel)).doesNotContain("aria-current");
        String target = link(html, otherLabel);
        var query = UriComponentsBuilder.fromUriString(target).build().getQueryParams();
        assertThat(query.keySet()).containsExactlyInAnyOrder("type", "q", "tag", "province", "area", "mode");
        assertThat(query.getFirst("type")).isEqualTo(otherType);
        assertThat(decode(query.getFirst("q"))).isEqualTo(text);
        assertThat(decode(query.getFirst("tag"))).isEqualTo(tag);
        assertThat(decode(query.getFirst("province"))).isEqualTo("Córdoba");
        assertThat(decode(query.getFirst("area"))).isEqualTo(area);
        assertThat(query.getFirst("mode")).isEqualTo("REMOTE");
        assertThat(html).contains("&lt;servicio&gt;", "&amp;").doesNotContain("<servicio>");
        String switched = mvc.perform(get(java.net.URI.create(target))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(attribute(element(switched, "input", "name", "type"), "value")).isEqualTo(otherType);
        assertThat(attribute(element(switched, "input", "name", "q"), "value")).isEqualTo(text);
        String reset = link(html, "Limpiar búsqueda");
        assertThat(reset).isEqualTo("/marketplace?type=" + type);
        String cleared = mvc.perform(get(java.net.URI.create(reset))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(attribute(element(cleared, "input", "name", "q"), "value")).isEmpty();
        assertThat(attribute(element(cleared, "input", "name", "type"), "value")).isEqualTo(type);
        assertThat(filters(cleared)).doesNotContain("open");
    }

    @ParameterizedTest
    @CsvSource({"tag,impresión", "province,Córdoba", "area,Villa María", "mode,REMOTE"})
    void shouldExposeEachActiveOptionalFilter(String name, String value) throws Exception {
        String html = mvc.perform(get("/marketplace").param(name, value)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(filters(html)).contains("open");
    }

    @Test
    void shouldKeepOptionalFiltersCollapsedWhenOnlyTextIsActive() throws Exception {
        assertThat(filters(page("/marketplace"))).doesNotContain("open");
        String html = mvc.perform(get("/marketplace").param("q", "impresión")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(filters(html)).doesNotContain("open");
    }

    @ParameterizedTest
    @CsvSource({"OFFER,REQUEST,Publicar lo que necesito", "REQUEST,OFFER,Publicar lo que ofrezco"})
    void shouldOfferTheMatchingNextStepWhenResultsAreEmpty(String type, String createType, String label) throws Exception {
        String html = mvc.perform(get("/marketplace").param("type", type).param("q", "sin-resultados-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("No encontramos publicaciones", "no se garantiza una respuesta");
        assertThat(link(html, label)).isEqualTo("/marketplace/create?type=" + createType);
        assertThat(html).doesNotContain("/marketplace/create?type=" + type);
    }

    @Test
    void shouldKeepInvalidQueriesRecoverableAndHomeAvailabilityTruthful() throws Exception {
        String invalidMode = mvc.perform(get("/marketplace").param("mode", "INVALID"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(invalidMode).contains("Revisá los campos", "href=\"/marketplace\"");
        mvc.perform(get("/marketplace").param("page", "-1")).andExpect(status().isForbidden());
        mvc.perform(get("/marketplace").param("province", "Invalid")).andExpect(status().isForbidden());
        String home = page("/");
        assertThat(home).contains("Conversaciones y trabajos privados", "pagos sintéticos", "revisión independiente",
                "producción y el dinero real siguen deshabilitados");
        assertThat(home).doesNotContain("Conversaciones, trabajos, suscripciones y pagos están pendientes");
    }

    @ParameterizedTest
    @CsvSource({"true,mode,INVALID,400", "true,page,-1,403", "false,mode,INVALID,400", "false,page,-1,403"})
    void shouldRenderCurrentSessionNavigationOnMarketplaceErrors(boolean authenticated, String name, String value,
                                                                 int expectedStatus) throws Exception {
        var request = get("/marketplace").param(name, value);
        MockHttpSession session = authenticated ? login() : null;
        if (session != null) {
            request.session(session);
        }
        String html = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(name.equals("mode") ? "Revisá los campos" : "Filtros inválidos");
        assertThat(link(html, "Volver a búsqueda")).isEqualTo("/marketplace");
        if (authenticated) {
            assertAccountNavigation(html);
            mvc.perform(get("/account").session(session)).andExpect(status().isOk());
        } else {
            assertThat(link(html, "Ingresar")).isEqualTo("/login");
            assertThat(link(html, "Crear cuenta")).isEqualTo("/register");
            assertThat(html).doesNotContain("action=\"/logout\"", "<summary>Mi cuenta</summary>");
        }
    }

    @Test
    void shouldKeepAccountNavigationOnCommonIdentityFailure() throws Exception {
        MockHttpSession session = login();
        String html = mvc.perform(post("/account/contact/verify").session(session).with(csrf())
                        .param("token", "invalid-synthetic-token"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Código inválido");
        assertAccountNavigation(html);
        mvc.perform(get("/account").session(session)).andExpect(status().isOk());
    }

    private MockHttpSession login() throws Exception {
        String contact = "usability-" + UUID.randomUUID() + "@example.test";
        String password = "Synthetic-password-12345";
        accounts.register(contact, password);
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", contact).param("password", password))
                .andExpect(redirectedUrl("/account")).andReturn().getRequest().getSession(false);
    }

    private static void assertAccountNavigation(String html) {
        String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
        assertThat(link(header, "Datos y preferencias")).isEqualTo("/account");
        assertThat(link(header, "Mis publicaciones")).isEqualTo("/marketplace/mine");
        assertThat(header).contains("<summary>Mi cuenta</summary>", "<summary>Pruebas y administración</summary>")
                .doesNotContain("href=\"/login\"", "href=\"/register\"");
        String logout = element(header, "form", "action", "/logout");
        assertThat(attribute(logout, "method")).isEqualToIgnoringCase("post");
        assertThat(attribute(element(logout, "input", "name", "_csrf"), "value")).isNotBlank();
    }

    private String page(String route) throws Exception {
        return mvc.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String filters(String html) {
        var match = Pattern.compile("<details\\b[^>]*>\\s*<summary>Filtros opcionales</summary>").matcher(html);
        assertThat(match.find()).isTrue();
        return match.group().substring(0, match.group().indexOf('>'));
    }

    private static String link(String html, String label) {
        return attribute(linkElement(html, label), "href");
    }

    private static String linkElement(String html, String label) {
        var match = Pattern.compile("<a\\b[^>]*>\\s*" + Pattern.quote(label) + "\\s*</a>").matcher(html);
        assertThat(match.find()).as("Rendered link %s", label).isTrue();
        return match.group();
    }

    private static String element(String html, String tag, String name, String value) {
        var match = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=\"" + Pattern.quote(value) + "\"[^>]*>"
                + (tag.equals("form") ? ".*?</form>" : ""), Pattern.DOTALL).matcher(html);
        assertThat(match.find()).as("Rendered %s with %s=%s", tag, name, value).isTrue();
        return match.group();
    }

    private static String attribute(String element, String name) {
        var match = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(element);
        assertThat(match.find()).as("Rendered attribute %s", name).isTrue();
        return HtmlUtils.htmlUnescape(match.group(1));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Explicit caller-owned test database requires " + name);
        }
        return value;
    }

    private static String decode(String value) {
        return UriUtils.decode(value, StandardCharsets.UTF_8);
    }
}
