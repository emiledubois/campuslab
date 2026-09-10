package cl.campuslab.catalog.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.catalog.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the approval saga's two new catalog endpoints (design doc §2.2/§3/§9 AC1/AC2/
 * AC4/AC6/AC8) against a real Postgres (Testcontainers) so the V3 ledger migration and
 * its idempotency guarantee are genuinely exercised. The concurrent-decrement race
 * (AC3) has its own dedicated test class since it needs a real HTTP server, not MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class StockAdjustmentApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));
    }

    @Test
    void decrement_withAvailableStock_decrementsAndReturns200() throws Exception {
        String id = createEquipo("Microscopio", 5);
        String bookingId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(4))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void decrement_withZeroStock_returns409WithoutChangingStock() throws Exception {
        String id = createEquipo("Microscopio agotado", 0);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient stock/cupo to approve this booking."));
    }

    @Test
    void decrement_withUnknownResourceId_returns404() throws Exception {
        mockMvc.perform(post("/api/catalog/resources/" + UUID.randomUUID() + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void decrement_withMalformedResourceId_returns400() throws Exception {
        mockMvc.perform(post("/api/catalog/resources/not-a-uuid/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decrement_withMissingBookingId_returns400() throws Exception {
        String id = createEquipo("Microscopio", 5);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decrement_withMalformedBookingId_returns400() throws Exception {
        String id = createEquipo("Microscopio", 5);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decrement_calledTwiceWithSameBookingId_isIdempotentAndDecrementsExactlyOnce() throws Exception {
        // AC6's server-side proof: the ledger, not a description of it - a retried
        // decrement call for the same (resourceId, bookingId) pair must not double-decrement.
        String id = createEquipo("Microscopio", 5);
        String bookingId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(4));

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(4));
    }

    @Test
    void incrementAfterDecrement_restoresStockAndRemovesLedgerEntry() throws Exception {
        // AC7's server-side proof: the compensating call actually restores the pre-
        // decrement value, not merely returns a status code.
        String id = createEquipo("Microscopio", 5);
        String bookingId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(4));

        mockMvc.perform(post("/api/catalog/resources/" + id + "/increment")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(5));

        // A second increment for the same pair is now the "already compensated" replay -
        // unchanged, not a further increment.
        mockMvc.perform(post("/api/catalog/resources/" + id + "/increment")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(bookingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(5));
    }

    @Test
    void increment_withUnknownResourceIdButNoLedgerEntry_returns404() throws Exception {
        mockMvc.perform(post("/api/catalog/resources/" + UUID.randomUUID() + "/increment")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void decrementAndIncrement_asEstudianteOrAuditor_return403() throws Exception {
        String id = createEquipo("Microscopio", 5);
        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer auditor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/resources/" + id + "/increment")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void decrement_asTecnico_returns200NotForbidden() throws Exception {
        // AC8's specific access-control resolution: TECNICO/ADMIN, not ADMIN-only.
        String id = createEquipo("Microscopio", 5);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isOk());
    }

    @Test
    void decrement_asAdmin_returns200() throws Exception {
        String id = createEquipo("Microscopio", 5);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isOk());
    }

    @Test
    void decrement_onLaboratorioResource_decrementsCupoNotStock() throws Exception {
        String id = createLaboratorio("Laboratorio de Redes", 10);

        mockMvc.perform(post("/api/catalog/resources/" + id + "/decrement")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cupo").value(9))
                .andExpect(jsonPath("$.stock").doesNotExist());
    }

    private String createEquipo(String name, int stock) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceType":"EQUIPO","name":"%s","description":"desc","location":"loc","stock":%d}
                                """.formatted(name, stock)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private String createLaboratorio(String name, int cupo) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceType":"LABORATORIO","name":"%s","description":"desc","location":"loc","cupo":%d}
                                """.formatted(name, cupo)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private static String bookingBody(String bookingId) {
        return """
                {"bookingId":"%s"}
                """.formatted(bookingId);
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("oid", subject + "-oid")
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
