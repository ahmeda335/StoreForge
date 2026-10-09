package dev.storeforge.shared.config;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * application.yml carries working secrets for LOCAL DEVELOPMENT (so the app runs with no setup).
 * They are public: anyone with the repository has them. With
 * {@code storeforge.security.allow-development-secrets=false} (every real deployment, P16
 * checklist; like {@code allow-ephemeral-signing-key}) the application refuses to START while any
 * of them is still in use, instead of logging a warning nobody reads. Values are never logged.
 */
@Component
@Slf4j
public class DevelopmentSecretsGuard implements SmartInitializingSingleton {

    /** Property → the development value committed in application.yml (kept in sync by a test). */
    static final Map<String, String> DEVELOPMENT_VALUES = Map.of(
            "storeforge.webhooks.encryption-key", "uJpdIXQiVMWS3GxRYbhcbQPA/RSV8pSJsHcBn1vA/0M=",
            "storeforge.platform.mfa-encryption-key", "Xy2rGv0aQm9mTqB7w5Zr3kN8pE1sU4dJ6hL0cV2bF9o=",
            "storeforge.payments.fake.webhook-secret", "whsec_fake_local_development_only",
            "storeforge.storage.secret-key", "storeforge-local-secret");

    private final Environment environment;

    public DevelopmentSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    public List<String> developmentSecretsInUse() {
        return DEVELOPMENT_VALUES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(environment.getProperty(entry.getKey())))
                .map(Map.Entry::getKey).sorted().toList();
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> inUse = developmentSecretsInUse();
        if (inUse.isEmpty()) {
            return;
        }
        if (!environment.getProperty("storeforge.security.allow-development-secrets", Boolean.class, true)) {
            throw new IllegalStateException("Refusing to start with development secrets (public, from application.yml) for: " + inUse
                    + ". Set real values, see docs/operations/runbooks/secrets.md.");
        }
        log.atWarn().addKeyValue("properties", inUse).log("Running with DEVELOPMENT secrets: local development only");
    }
}
