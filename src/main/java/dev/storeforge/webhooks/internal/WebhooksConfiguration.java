package dev.storeforge.webhooks.internal;

import dev.storeforge.shared.crypto.SecretBox;
import dev.storeforge.webhooks.internal.delivery.RetryPolicy;
import dev.storeforge.webhooks.internal.delivery.WebhookSender;
import dev.storeforge.webhooks.internal.security.Cidr;
import dev.storeforge.webhooks.internal.security.EndpointPolicy;
import java.net.InetAddress;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Slf4j
class WebhooksConfiguration {

    @Bean
    SecretBox webhookSecretBox(WebhooksProperties properties) {
        // A development key is refused at startup outside local development (DevelopmentSecretsGuard).
        return new SecretBox(Base64.getDecoder().decode(properties.encryptionKey()));
    }

    @Bean
    EndpointPolicy webhookEndpointPolicy(WebhooksProperties properties) {
        List<String> allowed = properties.allowedPrivateCidrs() == null ? List.of() : properties.allowedPrivateCidrs();
        if (!properties.requireHttps() || !allowed.isEmpty()) {
            log.warn("Webhook SSRF guard relaxed (requireHttps={}, allowedPrivateCidrs={}): local development and tests only",
                    properties.requireHttps(), allowed);
        }
        return new EndpointPolicy(properties.requireHttps(), allowed.stream().filter(s -> !s.isBlank()).map(Cidr::parse).toList(),
                InetAddress::getAllByName);
    }

    @Bean(destroyMethod = "close")
    WebhookSender webhookSender(EndpointPolicy policy, WebhooksProperties properties) {
        return new WebhookSender(policy, properties.connectTimeout(), properties.requestTimeout(), properties.maxConnectionsPerEndpoint(),
                properties.responseExcerptBytes(), "StoreForge-Webhooks/1.0");
    }

    @Bean
    RetryPolicy webhookRetryPolicy(WebhooksProperties properties) {
        return new RetryPolicy(properties.retrySchedule(), properties.maxAttempts(), properties.maxAttemptsOnClientError());
    }
}
