package cl.campuslab.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolesClaimResolverTest {

    private final RolesClaimResolver resolver = new RolesClaimResolver();

    @Test
    void resolve_withRolesClaimPresent_returnsMappedList() {
        Map<String, Object> claims = Map.of("roles", List.of("ADMIN", "TECNICO"));

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).containsExactly("ADMIN", "TECNICO");
    }

    @Test
    void resolve_withRolesClaimMissing_returnsEmptyList() {
        Map<String, Object> claims = Map.of("sub", "user-1");

        List<String> roles = resolver.resolve(claims);

        assertThat(roles).isEmpty();
    }
}
