package cl.campuslab.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.audit.AbstractIntegrationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the *real* Entra validator chain (issuer/audience/signature/expiry/alg:none)
 * against ms-campuslab-audit's production {@code JwtDecoderConfig} - unlike
 * SecurityIntegrationTest, JwtDecoder is never mocked here. Independent copy of the
 * same test-token/mock-issuer approach used across every other service in this project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class EntraJwtValidationTest extends AbstractIntegrationTest {

    private static final String ISSUER_PATH = "/test-tenant/v2.0";
    private static final String KEY_ID = "test-kid";
    private static final String AUDIENCE = "api://test-audience";
    private static final KeyPair KEY_PAIR;
    private static final HttpServer SERVER;
    private static final String ISSUER;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY_PAIR = generator.generateKeyPair();

            SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ISSUER = "http://localhost:" + SERVER.getAddress().getPort() + ISSUER_PATH;

            SERVER.createContext(ISSUER_PATH + "/.well-known/openid-configuration", exchange -> {
                String body = "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/discovery/v2.0/keys\"}";
                respondJson(exchange, body);
            });
            SERVER.createContext(ISSUER_PATH + "/discovery/v2.0/keys", exchange -> {
                RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic()).keyID(KEY_ID).build();
                respondJson(exchange, "{\"keys\":[" + publicJwk.toJSONString() + "]}");
            });
            SERVER.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock Entra discovery/JWKS server", e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("oidc.issuer-uri", () -> ISSUER);
        registry.add("oidc.audience", () -> AUDIENCE);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void getTimeline_withNoAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/audit/timeline")).andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withWrongIssuer_returns401() throws Exception {
        String token = mintSignedToken(
                "https://attacker.example.com/other-tenant/v2.0", AUDIENCE, "sub-1", "oid-1", List.of("ADMIN"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withWrongAudience_returns401() throws Exception {
        String token = mintSignedToken(
                ISSUER, "api://wrong-audience", "sub-1", "oid-1", List.of("ADMIN"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withExpiredToken_returns401() throws Exception {
        String token = mintSignedToken(
                ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("ADMIN"),
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(600), Instant.now().minusSeconds(60));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withTamperedSignature_returns401() throws Exception {
        String valid = mintSignedToken(
                ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("ADMIN"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));
        String tampered = flipMiddleSignatureCharacter(valid);
        assertSignatureBytesDiffer(valid, tampered);

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withUnsecuredAlgNoneToken_returns401() throws Exception {
        JWTClaimsSet claims = claimsBuilder(
                        ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("ADMIN"),
                        Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300))
                .build();
        String token = new PlainJWT(claims).serialize();

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTimeline_withTecnicoRole_returns403() throws Exception {
        String token = mintSignedToken(
                ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("TECNICO"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTimeline_withValidAdminToken_returns200() throws Exception {
        String token = mintSignedToken(
                ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("ADMIN"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getTimeline_withValidAuditorToken_returns200() throws Exception {
        String token = mintSignedToken(
                ISSUER, AUDIENCE, "sub-1", "oid-1", List.of("AUDITOR"),
                Instant.now(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/audit/timeline").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static JWTClaimsSet.Builder claimsBuilder(
            String issuer, String audience, String sub, String oid, List<String> roles,
            Instant issuedAt, Instant notBefore, Instant expiresAt) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(sub)
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiresAt));
        if (oid != null) {
            builder.claim("oid", oid);
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder;
    }

    private static String mintSignedToken(
            String issuer, String audience, String sub, String oid, List<String> roles,
            Instant issuedAt, Instant notBefore, Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = claimsBuilder(issuer, audience, sub, oid, roles, issuedAt, notBefore, expiresAt).build();
        return sign(claims);
    }

    private static String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        return jwt.serialize();
    }

    private static String flipMiddleSignatureCharacter(String token) {
        String[] parts = token.split("\\.");
        char[] signature = parts[2].toCharArray();
        int middle = signature.length / 2;
        signature[middle] = signature[middle] == 'A' ? 'B' : 'A';
        parts[2] = new String(signature);
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private static void assertSignatureBytesDiffer(String original, String tampered) {
        byte[] originalBytes = Base64.getUrlDecoder().decode(pad(original.split("\\.")[2]));
        byte[] tamperedBytes = Base64.getUrlDecoder().decode(pad(tampered.split("\\.")[2]));
        assertThat(tamperedBytes).isNotEqualTo(originalBytes);
    }

    private static String pad(String base64Url) {
        int remainder = base64Url.length() % 4;
        return remainder == 0 ? base64Url : base64Url + "=".repeat(4 - remainder);
    }
}
