package cl.campuslab.catalog.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.catalog.AbstractIntegrationTest;
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
 * Covers the CRUD/validation acceptance criteria from the design doc (creation,
 * role gating per verb, Bean Validation failures, 404/409 mapping) against a real
 * Postgres (Testcontainers) so the Flyway migration and its CHECK constraints are
 * genuinely exercised, not assumed. The concurrent-PUT race (AC15) has its own
 * dedicated test class since it needs a real HTTP server, not MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CatalogResourceApiTest extends AbstractIntegrationTest {

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
    void createLaboratorio_withValidBody_returns201WithServerAssignedIdAndVersionZero() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laboratorioBody("Laboratorio de Redes 3", 20)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.resourceType").value("LABORATORIO"))
                .andExpect(jsonPath("$.cupo").value(20))
                .andExpect(jsonPath("$.stock").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createEquipo_withValidBody_returns201() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equipoBody("Microscopio", 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceType").value("EQUIPO"))
                .andExpect(jsonPath("$.stock").value(5));
    }

    @Test
    void updateResource_withCorrectVersion_returns200AndIncrementsVersion() throws Exception {
        String id = createResource(laboratorioBody("Laboratorio de Redes 3", 20));

        mockMvc.perform(put("/api/catalog/resources/" + id)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Laboratorio de Redes 3", null, 18, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cupo").value(18))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getResources_asTecnico_returns200WithCreatedResourcesInList() throws Exception {
        createResource(laboratorioBody("Laboratorio visible", 10));

        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Laboratorio visible')]").exists());
    }

    @Test
    void createResource_asTecnico_returns403NotUnauthorized() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laboratorioBody("Lab", 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateResource_asTecnico_returns403() throws Exception {
        String id = createResource(laboratorioBody("Lab", 10));

        mockMvc.perform(put("/api/catalog/resources/" + id)
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Lab", null, 9, 0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAndCreateResources_asEstudiante_return403() throws Exception {
        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer estudiante-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laboratorioBody("Lab", 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAndCreateResources_asAuditor_return403() throws Exception {
        mockMvc.perform(get("/api/catalog/resources").header("Authorization", "Bearer auditor-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer auditor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laboratorioBody("Lab", 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createLaboratorio_withNegativeCupo_returns400NotServerError() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laboratorioBody("Lab", -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createResource_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceType":"LABORATORIO","cupo":10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createEquipo_withBothStockAndCupo_returns400() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceType":"EQUIPO","name":"Microscopio","stock":5,"cupo":3}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createResource_withUnknownResourceType_returns400() throws Exception {
        mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceType":"NO_EXISTE","name":"Lab","cupo":10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateResource_withUnknownId_returns404() throws Exception {
        mockMvc.perform(put("/api/catalog/resources/" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Lab", null, 10, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateResource_withMalformedId_returns400() throws Exception {
        mockMvc.perform(put("/api/catalog/resources/not-a-uuid")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Lab", null, 10, 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateResource_withStaleVersion_returns409WithReloadAndRetryMessage() throws Exception {
        String id = createResource(laboratorioBody("Lab", 20));

        mockMvc.perform(put("/api/catalog/resources/" + id)
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Lab", null, 18, 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The resource was modified by someone else; reload and retry."));
    }

    private String createResource(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/catalog/resources")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private static String laboratorioBody(String name, int cupo) {
        return """
                {"resourceType":"LABORATORIO","name":"%s","description":"desc","location":"loc","cupo":%d}
                """.formatted(name, cupo);
    }

    private static String equipoBody(String name, int stock) {
        return """
                {"resourceType":"EQUIPO","name":"%s","description":"desc","location":"loc","stock":%d}
                """.formatted(name, stock);
    }

    private static String updateBody(String name, Integer stock, Integer cupo, long version) {
        return """
                {"name":"%s","description":"desc","location":"loc","stock":%s,"cupo":%s,"version":%d}
                """.formatted(name, stock, cupo, version);
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("iss", "http://localhost:8081/realms/campuslab")
                .claim("realm_access", java.util.Map.of("roles", roles))
                .build();
    }
}
