package cl.campuslab.bookings.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    @Test
    void validate_withMatchingAudience_succeeds() {
        AudienceValidator validator = new AudienceValidator("campuslab-api");
        Jwt jwt = jwtWithAudience(List.of("campuslab-api", "other-api"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_withDifferentAudience_fails() {
        AudienceValidator validator = new AudienceValidator("campuslab-api");
        Jwt jwt = jwtWithAudience(List.of("some-other-api"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void validate_withNoAudienceClaim_fails() {
        AudienceValidator validator = new AudienceValidator("campuslab-api");
        Jwt jwt = jwtWithAudience(List.of());

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwtWithAudience(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "user-1");
        if (!audience.isEmpty()) {
            builder.claim("aud", audience);
        }
        return builder.build();
    }
}
