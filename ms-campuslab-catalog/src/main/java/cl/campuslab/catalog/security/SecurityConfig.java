package cl.campuslab.catalog.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * Catalog is never reachable from a browser (only from ms-campuslab-bff over the
     * compose network), so there is no CorsConfigurationSource here - unlike the BFF,
     * which is the browser's own entry point. Path rules mirror the BFF's own rules for
     * these same endpoints (defense in depth, see the design doc's section 2 decision).
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
                        .requestMatchers(HttpMethod.GET, "/api/catalog/resources").hasAnyRole("ADMIN", "TECNICO")
                        // Approval-driven stock operations (design doc §2.3): TECNICO/ADMIN, not
                        // ADMIN-only like general catalog administration below - bookings forwards
                        // the original TECNICO/ADMIN caller's own token unchanged for these two.
                        // Must be declared before the exact-path POST rule so they don't silently
                        // fall through to anyRequest().authenticated() with no role check at all.
                        .requestMatchers(HttpMethod.POST, "/api/catalog/resources/*/decrement").hasAnyRole("TECNICO", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/catalog/resources/*/increment").hasAnyRole("TECNICO", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/catalog/resources").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/resources/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler(restAccessDeniedHandler))
                .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesJwtAuthenticationConverter))
                        .authenticationEntryPoint(restAuthenticationEntryPoint));
        return http.build();
    }
}
