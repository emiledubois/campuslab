package cl.campuslab.bff.security;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Walks {@code OIDC_ROLES_CLAIM} (dot-separated path) through a claims map so the
 * same code reads Keycloak's nested {@code realm_access.roles} and Azure AD's flat
 * {@code roles} - the roles claim shape is configuration, never an {@code if} on issuer.
 */
@Component
public class RolesClaimResolver {

    private final String[] rolesClaimPath;

    public RolesClaimResolver(OidcProperties oidcProperties) {
        this.rolesClaimPath = oidcProperties.rolesClaim().split("\\.");
    }

    public List<String> resolve(Map<String, Object> claims) {
        Object current = claims;
        for (String segment : rolesClaimPath) {
            if (!(current instanceof Map<?, ?> map)) {
                return List.of();
            }
            current = map.get(segment);
        }
        if (current instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
