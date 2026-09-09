package cl.campuslab.bff.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oidc")
public record OidcProperties(String issuerUri, String audience, String rolesClaim) {
}
