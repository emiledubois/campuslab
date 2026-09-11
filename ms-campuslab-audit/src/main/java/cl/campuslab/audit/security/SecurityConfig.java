package cl.campuslab.audit.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * audit is never reachable from a browser (only from ms-campuslab-bff over the
     * compose network), so there is no CorsConfigurationSource here. {@code
     * GET /api/audit/timeline} is ADMIN/AUDITOR only (design doc §3/§7 A01) - this is
     * audit's first-ever guarded endpoint, enforced here as defense-in-depth alongside
     * the BFF's own new /api/audit/** rule.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RolesJwtAuthenticationConverter rolesJwtAuthenticationConverter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler(restAccessDeniedHandler))
                .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesJwtAuthenticationConverter))
                        .authenticationEntryPoint(restAuthenticationEntryPoint));
        return http.build();
    }
}
