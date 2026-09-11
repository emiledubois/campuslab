package cl.campuslab.report.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties(OidcProperties.class)
public class JwtDecoderConfig {

    /**
     * Built against the issuer's published JWKS only (NimbusJwtDecoder.withIssuerLocation),
     * fetched dynamically and cached - there is no static/HS256 fallback key configured
     * anywhere, so an unsigned or alg:none token has no matching key and is rejected.
     */
    @Bean
    public JwtDecoder jwtDecoder(OidcProperties oidcProperties) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withIssuerLocation(oidcProperties.issuerUri()).build();

        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(oidcProperties.issuerUri());
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(oidcProperties.audience());

        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, audienceValidator));
        return jwtDecoder;
    }
}
