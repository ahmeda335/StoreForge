package dev.storeforge.identity.internal.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * One security filter chain per API, matched by path (blueprint 08: the APIs deliberately have
 * different security models). Chains are tried in {@code @Order}; the first matching one handles
 * the request.
 *
 * <p>All chains are STATELESS (no HTTP session, no session cookie) and CSRF protection is off:
 * the APIs authenticate with an {@code Authorization: Bearer} header that browsers never attach
 * automatically, so a cross-site request cannot carry the user's credentials. The one
 * cookie-authenticated endpoint (token refresh) has its own explicit CSRF defenses.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /** /auth/**: public endpoints (register, login, refresh, ...); each protects itself with rate limits. */
    @Bean
    @Order(1)
    SecurityFilterChain authApi(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher("/auth/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /** /admin/**: merchant admin API; requires a valid merchant access token. */
    @Bean
    @Order(2)
    SecurityFilterChain adminApi(HttpSecurity http, UserTokenConverter converter, JsonMapper jsonMapper) throws Exception {
        var entryPoint = ProblemSecurityHandlers.authenticationEntryPoint(jsonMapper);
        return stateless(http.securityMatcher("/admin/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(ProblemSecurityHandlers.accessDeniedHandler(jsonMapper)))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new UserMdcFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * /storefront/**: the PUBLIC customer API. Anonymous by design; the store comes from the
     * hostname. Customer accounts and cart tokens (P7) add their own authentication here.
     */
    @Bean
    @Order(3)
    SecurityFilterChain storefrontApi(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher("/storefront/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /**
     * /webhooks/**: calls FROM payment providers. No user credentials: each request authenticates
     * itself with a signature over its raw body, verified by the payments module.
     */
    @Bean
    @Order(4)
    SecurityFilterChain providerWebhooks(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher("/webhooks/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /** Everything else (unknown paths → 404 problems; actuator on the private management port). */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain fallback(HttpSecurity http) throws Exception {
        return stateless(http)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }

    /**
     * Argon2id by default (memory-hard: GPUs/ASICs can't cheaply parallelize guesses). The
     * {argon2}/{bcrypt} prefix stored with each hash lets us change algorithm or cost later:
     * old hashes keep verifying and are re-hashed on the next successful login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    JwtKeys jwtKeys(AuthProperties properties) {
        return JwtKeys.from(properties);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.activeKey())));
    }

    /**
     * Validation is pinned, never negotiated by the token: only ES256 (no "alg: none", no HS256
     * signed with our public key), only our keys, and RFC 9068 claims (typ at+jwt, iss, aud,
     * client_id, exp/iat/nbf with a small clock-skew allowance).
     */
    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.verificationKeys()))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(JwtValidators.createAtJwtValidator()
                .issuer(properties.issuer())
                .audience(AccessTokenIssuer.AUDIENCE)
                .clientId(AccessTokenIssuer.CLIENT_ID)
                .build());
        return decoder;
    }
}
