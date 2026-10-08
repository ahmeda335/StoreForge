package dev.storeforge.developer.internal.oauth;

import dev.storeforge.developer.internal.credentials.ApiCredentialStore;
import dev.storeforge.shared.crypto.SecretTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AccessTokenResponseAuthenticationSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * The OAuth 2.0 authorization server for third-party apps (Spring Authorization Server).
 * Two session-based chains, separate from the stateless APIs:
 * <ul>
 *   <li>the protocol endpoints ({@code /oauth2/authorize}, {@code /oauth2/token}, metadata);</li>
 *   <li>the pages ({@code /oauth/login}, {@code /oauth/consent}): form login with CSRF protection.</li>
 * </ul>
 * The browser session they share NEVER authenticates the admin or developer APIs (those chains are
 * STATELESS and only accept bearer tokens), so it can't be abused for CSRF against them.
 */
@Configuration(proxyBeanMethods = false)
class OAuthConfiguration {

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${storeforge.auth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2AuthorizationService oauthAuthorizations(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new OAuthStorage.Authorizations(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService oauthConsents() {
        return new OAuthStorage.NoRememberedConsent();
    }

    @Bean
    @Order(-2)
    SecurityFilterChain oauthProtocol(HttpSecurity http, RegisteredClientRepository clients, OAuth2AuthorizationService authorizations,
                                      OAuth2AuthorizationConsentService consents, AuthorizationServerSettings settings,
                                      InstallationTokenGenerator tokens, ApiCredentialStore credentials) throws Exception {
        OAuth2AccessTokenResponseAuthenticationSuccessHandler tokenResponse = new OAuth2AccessTokenResponseAuthenticationSuccessHandler();
        // The app learns which store it was installed on (it only sent a hostname).
        tokenResponse.setAccessTokenResponseCustomizer(context -> {
            OAuth2AccessTokenAuthenticationToken token = context.getAuthentication();
            credentials.findActiveByHash(SecretTokens.hash(token.getAccessToken().getTokenValue())).ifPresent(credential ->
                    context.getAccessTokenResponse().additionalParameters(java.util.Map.of("store_id", credential.storeId().toString())));
        });
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.registeredClientRepository(clients)
                    .authorizationService(authorizations)
                    .authorizationConsentService(consents)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(tokens)
                    .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth/consent"))
                    .tokenEndpoint(endpoint -> endpoint.accessTokenResponseHandler(tokenResponse));
        });
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/oauth/login")));
        return http.build();
    }

    @Bean
    @Order(-1)
    SecurityFilterChain oauthPages(HttpSecurity http, PasswordAuthenticationProvider passwords) throws Exception {
        return http.securityMatcher("/oauth/**")
                .authenticationProvider(passwords)
                .authorizeHttpRequests(requests -> requests.requestMatchers("/oauth/login").permitAll().anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/oauth/login").loginProcessingUrl("/oauth/login").failureUrl("/oauth/login?error"))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/oauth/login")))
                .build();
    }
}
