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
    void ping_withAdminToken_returnsScopeOidAndRoles() {
        Jwt jwt = jwt("3f2a-uuid", "3f2a1c9e-oid");
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        AdminPingResponse response = controller.ping(authentication);

        assertThat(response.scope()).isEqualTo("admin-only");
        assertThat(response.oid()).isEqualTo("3f2a1c9e-oid");
        assertThat(response.roles()).containsExactly("ADMIN");
    }

    @Test
    void ping_withNoOidClaim_fallsBackToSubjectForDisplay() {
        Jwt jwt = jwt("3f2a-uuid", null);
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        AdminPingResponse response = controller.ping(authentication);

        assertThat(response.oid()).isEqualTo("3f2a-uuid");
    }

    private static Jwt jwt(String subject, String oid) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (oid != null) {
            builder.claim("oid", oid);
        }
        return builder.build();
    }
}
