package cl.campuslab.bff.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class RolesJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final RolesClaimResolver rolesClaimResolver;

    public RolesJwtAuthenticationConverter(RolesClaimResolver rolesClaimResolver) {
        this.rolesClaimResolver = rolesClaimResolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = rolesClaimResolver.resolve(jwt.getClaims()).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
