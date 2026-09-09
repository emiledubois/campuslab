package cl.campuslab.bff.web;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.bff.web.dto.AdminPingResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The ADMIN-only role gate itself is enforced by the SecurityConfig path rule
 * (/api/admin/** -> hasRole('ADMIN')), exercised end to end in
 * cl.campuslab.bff.security.SecurityIntegrationTest; this test only covers the
 * controller's own response-building logic once a caller has already passed that gate.
 */
class AdminPingControllerTest {

    private final AdminPingController controller = new AdminPingController();

    @Test
    void ping_withAdminToken_returnsScopeSubjectAndRoles() {
        Jwt jwt = jwt();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        AdminPingResponse response = controller.ping(authentication);

        assertThat(response.scope()).isEqualTo("admin-only");
        assertThat(response.sub()).isEqualTo("3f2a-uuid");
        assertThat(response.roles()).containsExactly("ADMIN");
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f2a-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
