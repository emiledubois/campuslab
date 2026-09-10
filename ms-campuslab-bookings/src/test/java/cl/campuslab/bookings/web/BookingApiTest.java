package cl.campuslab.bookings.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.bookings.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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
 * Covers the CRUD/ownership/state-machine acceptance criteria from the design doc
 * against a real Postgres (Testcontainers) so the Flyway migration and its CHECK
 * constraints are genuinely exercised, not assumed. The concurrent-PUT race (AC19)
 * has its own dedicated test class since it needs a real HTTP server, not MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BookingApiTest extends AbstractIntegrationTest {

    private static final String RESOURCE_ID = "5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("estudiante2-token")).willReturn(jwt("estudiante2-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        given(jwtDecoder.decode("auditor-token")).willReturn(jwt("auditor-uuid", List.of("AUDITOR")));
    }

    @Test
    void createBooking_withValidBody_returns201WithServerAssignedFields() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T10:00:00Z", "2026-09-15T12:00:00Z", "notes")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("SOLICITADA"))
                .andExpect(jsonPath("$.studentOid").value("estudiante-uuid"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void getBooking_ownedByCaller_returns200() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void listBookings_asEstudiante_returnsOnlyOwnBookingsNotOthers() throws Exception {
        String ownId = createBooking("estudiante-token");
        createBooking("estudiante2-token");

        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + ownId + "')]").exists())
                .andExpect(jsonPath("$[?(@.studentOid == 'estudiante2-uuid')]").doesNotExist());
    }

    @Test
    void getBooking_ownedByAnotherStudent_returns404NotForbidden() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer estudiante2-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void putStatus_calledByAnotherStudent_returns404() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer estudiante2-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("CANCELADA")))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBooking_asTecnico_returns200RegardlessOfOwner() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isOk());
    }

    @Test
    void putStatus_tecnicoApproving_returns200WithIncrementedVersion() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("APROBADA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADA"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void putStatus_fullLegalChain_succeedsOneStepAtATime() throws Exception {
        String id = createBooking("estudiante-token");
        putStatus(id, "tecnico-token", "APROBADA").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "EN_PREPARACION").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "EN_USO").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "DEVUELTA").andExpect(status().isOk());
    }

    @Test
    void putStatus_illegalSkipForwardFromSolicitadaToEnUso_returns409() throws Exception {
        String id = createBooking("estudiante-token");

        putStatus(id, "tecnico-token", "EN_USO").andExpect(status().isConflict());
    }

    @Test
    void putStatus_illegalSkipForwardFromAprobadaToEnUso_returns409() throws Exception {
        String id = createBooking("estudiante-token");
        putStatus(id, "tecnico-token", "APROBADA").andExpect(status().isOk());

        putStatus(id, "tecnico-token", "EN_USO").andExpect(status().isConflict());
    }

    @Test
    void putStatus_onTerminalDevuelta_returns409() throws Exception {
        String id = createBooking("estudiante-token");
        putStatus(id, "tecnico-token", "APROBADA").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "EN_PREPARACION").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "EN_USO").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "DEVUELTA").andExpect(status().isOk());

        putStatus(id, "tecnico-token", "APROBADA").andExpect(status().isConflict());
    }

    @Test
    void putStatus_estudianteRequestingAprobadaOnOwnSolicitada_returns403() throws Exception {
        String id = createBooking("estudiante-token");

        putStatus(id, "estudiante-token", "APROBADA").andExpect(status().isForbidden());
    }

    @Test
    void putStatus_estudianteCancellingFromSolicitada_returns200() throws Exception {
        String id = createBooking("estudiante-token");

        putStatus(id, "estudiante-token", "CANCELADA").andExpect(status().isOk());
    }

    @Test
    void putStatus_estudianteCancellingFromEnPreparacion_returns409() throws Exception {
        String id = createBooking("estudiante-token");
        putStatus(id, "tecnico-token", "APROBADA").andExpect(status().isOk());
        putStatus(id, "tecnico-token", "EN_PREPARACION").andExpect(status().isOk());

        putStatus(id, "estudiante-token", "CANCELADA").andExpect(status().isConflict());
    }

    @Test
    void createBooking_missingResourceId_returns400() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedStart":"2026-09-15T10:00:00Z","requestedEnd":"2026-09-15T12:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBooking_malformedRequestedStart_returns400() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceId":"%s","requestedStart":"not-a-date","requestedEnd":"2026-09-15T12:00:00Z"}
                                """.formatted(RESOURCE_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBooking_endNotAfterStart_returns400() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T12:00:00Z", "2026-09-15T10:00:00Z", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBooking_malformedResourceId_returns400() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceId":"not-a-uuid","requestedStart":"2026-09-15T10:00:00Z","requestedEnd":"2026-09-15T12:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBooking_malformedId_returns400() throws Exception {
        mockMvc.perform(get("/api/bookings/not-a-uuid").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putStatus_malformedId_returns400() throws Exception {
        mockMvc.perform(put("/api/bookings/not-a-uuid/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody("APROBADA")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putStatus_unrecognizedStatusLiteral_returns400() throws Exception {
        String id = createBooking("estudiante-token");

        putStatus(id, "tecnico-token", "NO_EXISTE").andExpect(status().isBadRequest());
    }

    @Test
    void putStatus_missingStatusField_returns400() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminHasSameStatusChangeGrantsAsTecnico() throws Exception {
        String approve = createBooking("estudiante-token");
        putStatus(approve, "admin-token", "APROBADA").andExpect(status().isOk());

        String skip = createBooking("estudiante-token");
        putStatus(skip, "admin-token", "EN_USO").andExpect(status().isConflict());

        String cancelWindow = createBooking("estudiante-token");
        putStatus(cancelWindow, "estudiante-token", "CANCELADA").andExpect(status().isOk());
    }

    @Test
    void createBooking_asAdminOrTecnico_returns403() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T10:00:00Z", "2026-09-15T12:00:00Z", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T10:00:00Z", "2026-09-15T12:00:00Z", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditor_hasNoGrantOnAnyBookingsEndpoint() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer auditor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T10:00:00Z", "2026-09-15T12:00:00Z", null)))
                .andExpect(status().isForbidden());
        putStatus(id, "auditor-token", "APROBADA").andExpect(status().isForbidden());
    }

    @Test
    void listBookings_withInvalidStatusFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/bookings?status=NO_EXISTE").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listBookings_withFromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/bookings?from=2026-09-15T12:00:00Z&to=2026-09-15T10:00:00Z")
                        .header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isBadRequest());
    }

    private String createBooking(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(RESOURCE_ID, "2026-09-15T10:00:00Z", "2026-09-15T12:00:00Z", "notes")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions putStatus(String id, String token, String status) throws Exception {
        return mockMvc.perform(put("/api/bookings/" + id + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(status)));
    }

    private static String createBody(String resourceId, String start, String end, String notes) {
        return """
                {"resourceId":"%s","requestedStart":"%s","requestedEnd":"%s","notes":%s}
                """.formatted(resourceId, start, end, notes == null ? "null" : "\"" + notes + "\"");
    }

    private static String statusBody(String status) {
        return """
                {"status":"%s"}
                """.formatted(status);
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
