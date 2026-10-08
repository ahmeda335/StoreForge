package dev.storeforge.developer.internal;

import dev.storeforge.developer.internal.credentials.ApiCredentialIntrospector;
import dev.storeforge.developer.internal.ratelimit.ApiRateLimiter;
import dev.storeforge.developer.internal.ratelimit.RedisConnections;
import dev.storeforge.developer.internal.ratelimit.UsageCounters;
import dev.storeforge.developer.internal.web.UsageFilter;
import dev.storeforge.shared.web.ProblemResponses;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class DeveloperConfiguration {

    /**
     * /api/**: the developer API. Authenticated by opaque bearer tokens (API keys, app tokens),
     * looked up per request. Ordered first: its credentials mean nothing to the other chains.
     */
    @Bean
    @Order(0)
    SecurityFilterChain developerApi(HttpSecurity http, ApiCredentialIntrospector introspector, JsonMapper jsonMapper,
                                     UsageCounters usage, Clock clock) throws Exception {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            bearer.commence(request, response, exception);   // 401 + WWW-Authenticate: Bearer error="invalid_token"
            ProblemResponses.write(jsonMapper, request, response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "A valid API key or app access token is required (Authorization: Bearer ...).");
        };
        return http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .opaqueToken(opaque -> opaque.introspector(introspector))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new UsageFilter(usage, clock), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean(destroyMethod = "close")
    RedisConnections redisConnections(DeveloperProperties.Redis redis, Clock clock) {
        return new RedisConnections(redis, clock);
    }

    /** Opens after repeated Redis failures: rate limiting and usage then skip Redis immediately. */
    @Bean
    CircuitBreaker redisCircuitBreaker(MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry.circuitBreaker("redis");
    }

    @Bean
    ApiRateLimiter apiRateLimiter(RedisConnections redis, CircuitBreaker redisCircuitBreaker, DeveloperProperties properties,
                                  MeterRegistry meterRegistry) {
        return new ApiRateLimiter(redis, redisCircuitBreaker, properties, meterRegistry);
    }

    @Bean
    UsageCounters usageCounters(RedisConnections redis, CircuitBreaker redisCircuitBreaker, JdbcClient jdbc, TransactionTemplate transaction,
                                DeveloperProperties properties, MeterRegistry meterRegistry) {
        return new UsageCounters(redis, redisCircuitBreaker, jdbc, transaction, properties.usageRetentionInRedis(), meterRegistry);
    }

    /**
     * Redis down is DEGRADED, not DOWN: the app keeps serving (ADR-020). DOWN would turn the overall
     * /actuator/health into 503 and make monitors call a working app dead (found in EXP-014).
     * Not part of readiness either.
     */
    @Bean
    HealthIndicator redisHealthIndicator(RedisConnections redis) {
        return () -> redis.ping() ? Health.up().build()
                : Health.status("DEGRADED").withDetail("impact", "rate limits per instance; usage not recorded").build();
    }

    @Bean
    OpenAPI developerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("StoreForge developer API").version("2026-10")
                        .description("Store data for your integrations. Authenticate with an API key or app access token "
                                + "(Authorization: Bearer ...). Every endpoint lists the scope it requires."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description("sfk_… API key or sfat_… app token")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
