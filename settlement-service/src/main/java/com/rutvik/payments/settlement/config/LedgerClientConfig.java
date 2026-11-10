package com.rutvik.payments.settlement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class LedgerClientConfig {

    private static final AnonymousAuthenticationToken SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
            "settlement-service", "settlement-service", AuthorityUtils.createAuthorityList("ROLE_SERVICE"));

    /**
     * Client-credentials manager that works off the request thread (we call the ledger from
     * Kafka listener threads, where there is no servlet request or logged-in user).
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient ledgerRestClient(RestClient.Builder builder, OAuth2AuthorizedClientManager manager,
                                @Value("${ledger.base-url}") String baseUrl,
                                @Value("${ledger.connect-timeout:1s}") Duration connectTimeout,
                                @Value("${ledger.read-timeout:3s}") Duration readTimeout) {
        var oauth = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth.setClientRegistrationIdResolver(request -> "ledger");
        // The ledger call is made as this service, even when an operator's request triggered it.
        oauth.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .requestInterceptor(oauth)
                .build();
    }
}
