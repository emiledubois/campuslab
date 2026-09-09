package cl.campuslab.bookings.security;

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
     * Bookings is never reachable from a browser (only from ms-campuslab-bff over the
     * compose network), so there is no CorsConfigurationSource here - unlike the BFF,
     * which is the browser's own entry point. Path rules mirror the BFF's own rules for
     * these same endpoints (defense in depth, see design doc's §2 decision). Every path
     * here names only ESTUDIANTE/TECNICO/ADMIN explicitly, so AUDITOR is rejected by that
     * specific matcher and never falls through to the looser anyRequest().authenticated().
     * ESTUDIANTE-only role-appropriateness for the PUT .../status target value (e.g. a
     * student targeting APROBADA) is a business rule enforced in the service layer, not
     * here - SecurityConfig only gates who may reach the endpoint at all.
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
                        .requestMatchers(HttpMethod.POST, "/api/bookings").hasRole("ESTUDIANTE")
                        .requestMatchers(HttpMethod.GET, "/api/bookings").hasAnyRole("ESTUDIANTE", "TECNICO", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/bookings/*").hasAnyRole("ESTUDIANTE", "TECNICO", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/bookings/*/status").hasAnyRole("ESTUDIANTE", "TECNICO", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .accessDeniedHandler(restAccessDeniedHandler))
                .oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> oauth2) -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesJwtAuthenticationConverter))
                        .authenticationEntryPoint(restAuthenticationEntryPoint));
        return http.build();
    }
}
