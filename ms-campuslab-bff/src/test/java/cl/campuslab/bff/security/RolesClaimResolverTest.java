package cl.campuslab.bff.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolesClaimResolverTest {

    @Test
    void resolve_withNestedKeycloakStylePath_returnsRoles() {
        RolesClaimResolver resolver = new RolesClaimResolver(new OidcProperties("issuer", "aud", "realm_access.roles"));
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("ADMIN", "TECNICO")));

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).containsExactly("ADMIN", "TECNICO");
    }

    @Test
    void resolve_withFlatAzureAdStylePath_returnsRoles() {
        RolesClaimResolver resolver = new RolesClaimResolver(new OidcProperties("issuer", "aud", "roles"));
        Map<String, Object> claims = Map.of("roles", List.of("ESTUDIANTE"));

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).containsExactly("ESTUDIANTE");
    }

    @Test
    void resolve_withMissingClaim_returnsEmptyList() {
        RolesClaimResolver resolver = new RolesClaimResolver(new OidcProperties("issuer", "aud", "realm_access.roles"));
        Map<String, Object> claims = Map.of("sub", "user-1");

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).isEmpty();
    }

    @Test
    void resolve_withNonListValueAtPath_returnsEmptyList() {
        RolesClaimResolver resolver = new RolesClaimResolver(new OidcProperties("issuer", "aud", "roles"));
        Map<String, Object> claims = Map.of("roles", "not-a-list");

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).isEmpty();
    }
}
