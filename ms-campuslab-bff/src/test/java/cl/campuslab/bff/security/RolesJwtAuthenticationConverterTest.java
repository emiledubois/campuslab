package cl.campuslab.bff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class RolesJwtAuthenticationConverterTest {

    @Mock
    private RolesClaimResolver rolesClaimResolver;

    @Test
    void convert_mapsResolvedRolesToRolePrefixedAuthorities() {
        RolesJwtAuthenticationConverter converter = new RolesJwtAuthenticationConverter(rolesClaimResolver);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "user-1")
                .build();
        when(rolesClaimResolver.resolve(jwt.getClaims())).thenReturn(List.of("ADMIN", "TECNICO"));

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TECNICO");
    }

    @Test
    void convert_withNoRoles_returnsNoAuthorities() {
        RolesJwtAuthenticationConverter converter = new RolesJwtAuthenticationConverter(rolesClaimResolver);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "user-1")
                .build();
        when(rolesClaimResolver.resolve(jwt.getClaims())).thenReturn(List.of());

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).isEmpty();
    }
}
