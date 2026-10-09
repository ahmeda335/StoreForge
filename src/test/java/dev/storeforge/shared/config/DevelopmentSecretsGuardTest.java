package dev.storeforge.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevelopmentSecretsGuardTest {

    /** The guard's list must be exactly the development defaults committed in application.yml. */
    @Test
    void knowsEveryDevelopmentSecretInApplicationYml() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        DevelopmentSecretsGuard.DEVELOPMENT_VALUES.values().forEach(value -> assertThat(yml).contains(":" + value + "}"));
    }

    @Test
    void refusesToStartWithDevelopmentSecretsWhenNotAllowed() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("storeforge.security.allow-development-secrets", "false")
                .withProperty("storeforge.webhooks.encryption-key", DevelopmentSecretsGuard.DEVELOPMENT_VALUES.get("storeforge.webhooks.encryption-key"))
                .withProperty("storeforge.storage.secret-key", "a-real-secret");
        assertThatThrownBy(() -> new DevelopmentSecretsGuard(environment).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storeforge.webhooks.encryption-key")
                .hasMessageNotContaining("uJpd");
    }

    @Test
    void startsWithRealSecretsOrWhenDevelopmentSecretsAreAllowed() {
        new DevelopmentSecretsGuard(new MockEnvironment().withProperty("storeforge.security.allow-development-secrets", "false")
                .withProperty("storeforge.storage.secret-key", "a-real-secret")).afterSingletonsInstantiated();
        new DevelopmentSecretsGuard(new MockEnvironment()
                .withProperty("storeforge.storage.secret-key", "storeforge-local-secret")).afterSingletonsInstantiated();
    }
}
