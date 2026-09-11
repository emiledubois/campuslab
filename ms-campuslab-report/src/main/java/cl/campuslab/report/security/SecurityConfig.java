package cl.campuslab.report.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * report is never reachable from a browser (only from ms-campuslab-bff over the
     * compose network), so there is no CorsConfigurationSource here. {@code
     * GET /api/report/**} is ADMIN only (design doc §3/§7 A01, confirmed against §6's
     * pantalla table: Reportería's row names {@code Admin} alone, unlike Auditoría's
     * {@code Admin, Auditor}) - AUDITOR is deliberately excluded here, enforced as
     * defense-in-depth alongside the BFF's own new /api/report/** rule.
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
                        .requestMatchers("/api/report/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler(restAccessDeniedHandler))
                .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesJwtAuthenticationConverter))
                        .authenticationEntryPoint(restAuthenticationEntryPoint));
        return http.build();
    }
}
