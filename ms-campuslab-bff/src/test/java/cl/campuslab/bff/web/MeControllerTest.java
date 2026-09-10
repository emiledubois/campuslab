package cl.campuslab.bff.web;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.bff.web.dto.MeResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MeControllerTest {

    private final MeController controller = new MeController();

    @Test
    void me_withFullClaims_returnsIdentityFromValidatedToken() {
        Jwt jwt = jwt(builder -> builder
                .claim("preferred_username", "estudiante.test")
                .claim("email", "estudiante.test@campuslab.local")
                .claim("oid", "3f2a1c9e-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0"));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(role("ESTUDIANTE")));

        MeResponse response = controller.me(authentication);

        assertThat(response.sub()).isEqualTo("3f2a-uuid");
        assertThat(response.oid()).isEqualTo("3f2a1c9e-oid");
        assertThat(response.username()).isEqualTo("estudiante.test");
        assertThat(response.email()).isEqualTo("estudiante.test@campuslab.local");
        assertThat(response.roles()).containsExactly("ESTUDIANTE");
        assertThat(response.issuer()).isEqualTo("https://login.microsoftonline.com/test-tenant/v2.0");
    }

    @Test
    void me_withMissingPreferredUsername_fallsBackToOtherStandardClaims() {
        Jwt jwt = jwt(builder -> builder
                .claim("email", "admin.test@campuslab.local")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0"));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(role("ADMIN")));

        MeResponse response = controller.me(authentication);

        assertThat(response.username()).isEqualTo("admin.test@campuslab.local");
    }

    @Test
    void me_withNoDisplayClaimsAtAll_fallsBackToSubject() {
        Jwt jwt = jwt(builder -> builder.claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0"));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(role("AUDITOR")));

        MeResponse response = controller.me(authentication);

        assertThat(response.username()).isEqualTo("3f2a-uuid");
        assertThat(response.email()).isEqualTo("3f2a-uuid");
        assertThat(response.oid()).isEqualTo("3f2a-uuid");
    }

    @Test
    void me_returnsAuthoritiesWithInternalRolePrefixStripped() {
        Jwt jwt = jwt(builder -> builder.claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0"));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of(role("ADMIN"), role("TECNICO")));

        MeResponse response = controller.me(authentication);

        assertThat(response.roles()).containsExactlyInAnyOrder("ADMIN", "TECNICO");
    }

    private static GrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }

    private static Jwt jwt(java.util.function.Consumer<Jwt.Builder> customizer) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f2a-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        customizer.accept(builder);
        return builder.build();
    }
}
