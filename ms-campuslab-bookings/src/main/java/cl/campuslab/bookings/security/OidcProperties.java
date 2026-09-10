package cl.campuslab.bookings.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oidc")
public record OidcProperties(String issuerUri, String audience) {
}
