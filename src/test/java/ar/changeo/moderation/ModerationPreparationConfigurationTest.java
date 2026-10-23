package ar.changeo.moderation;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ModerationPreparationConfigurationTest {
    private final ModerationPreparationConfiguration configuration=new ModerationPreparationConfiguration();
    private MockEnvironment enabled() {
        var environment=new MockEnvironment();environment.setActiveProfiles("sandbox");
        environment.setProperty("changeo.moderation-preparation","openai-wire");environment.setProperty("changeo.synthetic-evidence","true");
        environment.setProperty("changeo.moderation-preparation-origin","http://127.0.0.1:12345");
        environment.setProperty("changeo.moderation-preparation-credential",Path.of(".runtime/external-moderation-preparation-20261020/fake.credential").toAbsolutePath().toString());
        return environment;
    }
    @Test void disabledDefaultCreatesNoClientOrWorkerAndDoesNotReadCredential() {
        new ApplicationContextRunner().withUserConfiguration(ModerationPreparationConfiguration.class)
                .withPropertyValues("changeo.moderation-preparation-credential=/missing-fixture")
                .run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(OpenAiReviewClient.class).doesNotHaveBean(ModerationPreparationWorker.class);});
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:12345","http://127.0.0.2:12345","https://127.0.0.1:12345","http://127.0.0.1:12345/","http://127.0.0.1:12345/v1","http://user@127.0.0.1:12345","http://127.0.0.1:12345?q=x","http://127.0.0.1:12345#x","http://[::1]:12345","https://api.openai.com"})
    void rejectsUnreleasedOriginsBeforeCredentialRead(String origin) {
        var environment=enabled().withProperty("changeo.moderation-preparation-origin",origin);
        assertThatThrownBy(()->configuration.moderationPreparationSettings(environment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OpenAiReviewClient.validateOrigin(URI.create(origin))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"synthetic","realMoney","evidence","profile","unknownMode","credentialScope"})
    void refusesInvalidCompositionBeforeClientCreation(String mode) {
        var environment=enabled();
        switch(mode) {
            case "synthetic"->environment.setProperty("changeo.synthetic-moderation","true");
            case "realMoney"->environment.setProperty("changeo.real-money","true");
            case "evidence"->environment.setProperty("changeo.synthetic-evidence","false");
            case "profile"->environment.setActiveProfiles("other");
            case "unknownMode"->environment.setProperty("changeo.moderation-preparation","anything");
            case "credentialScope"->environment.setProperty("changeo.moderation-preparation-credential","/tmp/outside");
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->configuration.moderationPreparationSettings(environment)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readsOnlyPrivateOwnedFakeCredentialAndRejectsOrdinaryKeyAndSymlink() throws Exception {
        Path root=Path.of(".runtime/external-moderation-preparation-20261020/config-"+UUID.randomUUID());
        Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path key=root.resolve("fake.credential");
        Files.writeString(key,OpenAiReviewClientTest.CREDENTIAL,StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
        assertThat(ModerationPreparationConfiguration.readCredential(key)).isEqualTo(OpenAiReviewClientTest.CREDENTIAL);
        Path ordinary=root.resolve("ordinary.credential");Files.writeString(ordinary,"sk-artificial-unusable-value");Files.setPosixFilePermissions(ordinary,PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(()->ModerationPreparationConfiguration.readCredential(ordinary)).isInstanceOf(IllegalArgumentException.class);
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(()->ModerationPreparationConfiguration.readCredential(key)).isInstanceOf(IllegalArgumentException.class);
        Path link=root.resolve("link.credential");Files.createSymbolicLink(link,key.toAbsolutePath());
        assertThatThrownBy(()->ModerationPreparationConfiguration.readCredential(link)).isInstanceOf(IllegalArgumentException.class);
    }
}
