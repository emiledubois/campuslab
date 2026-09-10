package cl.campuslab.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import cl.campuslab.catalog.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * AC15: two PUT requests, both read with version 0, must actually race on the wire -
 * not be simulated sequentially - so this uses a real embedded HTTP server
 * (TestRestTemplate against RANDOM_PORT) and two real threads released by a shared
 * CountDownLatch at the same instant, backed by a real Postgres (Testcontainers).
 * Postgres's row-level locking on the UPDATE ... WHERE id=? AND version=? statement
 * is what actually serializes the two concurrent transactions - Hibernate's optimistic
 * lock check (throwing ObjectOptimisticLockingFailureException, mapped to 409) is what
 * then makes the loser visible as a 409 rather than a silently lost update.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentUpdateRaceTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode("admin-token")).willReturn(jwt("admin-uuid", List.of("ADMIN")));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void twoConcurrentPuts_onSameResourceAndVersion_exactlyOneSucceeds() throws Exception {
        // Arrange: create one LABORATORIO resource, both racers will read it at version 0.
        String id = createResource();
        String bodyA = updateBody("Laboratorio A", 18, 0);
        String bodyB = updateBody("Laboratorio B", 15, 0);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<String>> raceA = () -> {
            startLatch.await();
            return put(id, bodyA);
        };
        Callable<ResponseEntity<String>> raceB = () -> {
            startLatch.await();
            return put(id, bodyB);
        };

        // Act: release both threads at the same instant so they genuinely race on the wire.
        Future<ResponseEntity<String>> futureA = executor.submit(raceA);
        Future<ResponseEntity<String>> futureB = executor.submit(raceB);
        startLatch.countDown();
        ResponseEntity<String> resultA = futureA.get(30, TimeUnit.SECONDS);
        ResponseEntity<String> resultB = futureB.get(30, TimeUnit.SECONDS);

        // Assert: exactly one 200 (version -> 1), exactly one 409, never two 200s.
        List<HttpStatus> statuses = List.of(
                HttpStatus.valueOf(resultA.getStatusCode().value()),
                HttpStatus.valueOf(resultB.getStatusCode().value()));
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);

        ResponseEntity<String> winner = resultA.getStatusCode().is2xxSuccessful() ? resultA : resultB;
        JsonNode winnerBody = objectMapper.readTree(winner.getBody());
        assertThat(winnerBody.get("version").asLong()).isEqualTo(1L);

        // Act again: the loser re-GETs the fresh version and retries - it must now succeed.
        ResponseEntity<String> refreshed = restTemplate.exchange(
                "/api/catalog/resources", HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        JsonNode list = objectMapper.readTree(refreshed.getBody());
        long freshVersion = 0;
        for (JsonNode node : list) {
            if (node.get("id").asText().equals(id)) {
                freshVersion = node.get("version").asLong();
            }
        }
        ResponseEntity<String> retry = put(id, updateBody("Laboratorio final", 10, freshVersion));

        // Assert: the retried PUT with the fresh version now succeeds.
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
    }

    private String createResource() throws Exception {
        HttpHeaders headers = authHeaders();
        String body = """
                {"resourceType":"LABORATORIO","name":"Lab concurrencia","description":"desc","location":"loc","cupo":20}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/catalog/resources", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        return json.get("id").asText();
    }

    private ResponseEntity<String> put(String id, String body) {
        HttpHeaders headers = authHeaders();
        return restTemplate.exchange(
                "/api/catalog/resources/" + id, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer admin-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String updateBody(String name, int cupo, long version) {
        return """
                {"name":"%s","description":"desc","location":"loc","stock":null,"cupo":%d,"version":%d}
                """.formatted(name, cupo, version);
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
