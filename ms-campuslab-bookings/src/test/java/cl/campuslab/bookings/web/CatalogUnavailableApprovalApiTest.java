package cl.campuslab.bookings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.bookings.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Design doc §9 AC5: with catalog unreachable, approval must fail deterministically
 * (503) within the configured timeout window, not hang. {@code catalog.service-url}
 * points at a local port that was bound then immediately closed, so the connection is
 * refused near-instantly - a genuine connection failure, not a simulated one - and this
 * class's own {@code CATALOG_CLIENT_CONNECT_TIMEOUT_MS} override keeps the bound, in
 * case the OS-level refusal is ever slower than expected in some environment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CatalogUnavailableApprovalApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void catalogProperties(DynamicPropertyRegistry registry) {
        registry.add("catalog.service-url", () -> "http://127.0.0.1:" + closedLocalPort());
        registry.add("catalog.client.connect-timeout-ms", () -> "1000");
        registry.add("catalog.client.read-timeout-ms", () -> "1000");
    }

    private static int closedLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
    }

    @Test
    void approve_withCatalogUnreachable_returns503WithinTimeoutWindowAndBookingStaysSolicitada() throws Exception {
        String id = createBooking();

        long start = System.currentTimeMillis();
        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APROBADA"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(
                        "The catalog service is currently unavailable; the booking was not approved."));
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs).isLessThan(10_000);
    }

    private String createBooking() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceId":"%s","requestedStart":"2026-09-15T10:00:00Z","requestedEnd":"2026-09-15T12:00:00Z","notes":null}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", subject)
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
