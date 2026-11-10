package com.rutvik.payments.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource server. Tokens come from Keycloak; scopes decide what a caller may do.
 * Only the settlement service's client gets ledger.post, the raw postings endpoint.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/postings").hasAuthority("SCOPE_ledger.post")
                        .requestMatchers(HttpMethod.POST, "/transfers").hasAuthority("SCOPE_ledger.write")
                        .requestMatchers(HttpMethod.POST, "/accounts", "/accounts/*/deposits", "/reconciliation/**")
                        .hasAuthority("SCOPE_ledger.admin")
                        .requestMatchers(HttpMethod.GET, "/**").hasAuthority("SCOPE_ledger.read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }
}
