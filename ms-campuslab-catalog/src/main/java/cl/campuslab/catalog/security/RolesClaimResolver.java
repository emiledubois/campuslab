package cl.campuslab.catalog.security;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Entra ID is the only issuer, in every environment (docs/DECISIONES_PROFESOR.md #6) -
 * there is exactly one roles-claim shape (a flat {@code roles} list), so this reads it
 * directly rather than walking a configurable dot-path.
 */
@Component
public class RolesClaimResolver {

    private static final String ROLES_CLAIM = "roles";

    public List<String> resolve(Map<String, Object> claims) {
        Object rolesValue = claims.get(ROLES_CLAIM);
        if (rolesValue instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
