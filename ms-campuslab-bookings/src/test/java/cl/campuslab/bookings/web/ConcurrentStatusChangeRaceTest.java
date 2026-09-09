package cl.campuslab.bookings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import cl.campuslab.bookings.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * AC19: two PUT .../status requests, both reading the same SOLICITADA booking, must
 * actually race on the wire - not be simulated sequentially - so this uses a real
 * embedded HTTP server (TestRestTemplate against RANDOM_PORT) and two real threads
 * released by a shared CountDownLatch at the same instant, backed by a real Postgres
 * (Testcontainers). Postgres's row-level locking on the UPDATE ... WHERE id=? AND
 * version=? statement is what actually serializes the two concurrent transactions -
 * Hibernate's optimistic lock check (throwing ObjectOptimisticLockingFailureException,
 * mapped to 409) is what then makes the loser visible as a 409 rather than a
 * silently lost update, same mechanism catalog's own ConcurrentUpdateRaceTest proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentStatusChangeRaceTest extends AbstractIntegrationTest {

    private static final String RESOURCE_ID = "5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void twoConcurrentStatusChanges_onSameSolicitadaBooking_exactlyOneSucceeds() throws Exception {
        // Arrange: create one SOLICITADA booking, both racers will read it at version 0.
        String id = createBooking();
        String approveBody = statusBody("APROBADA");
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<String>> raceA = () -> {
            startLatch.await();
            return put(id, approveBody);
        };
        Callable<ResponseEntity<String>> raceB = () -> {
            startLatch.await();
            return put(id, approveBody);
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
        assertThat(winnerBody.get("status").asText()).isEqualTo("APROBADA");

        // Assert: the loser's 409 carries the optimistic-lock detail message, distinct
        // from an illegal-transition 409 (design doc §3 step 6 vs step 7).
        ResponseEntity<String> loser = resultA.getStatusCode().is2xxSuccessful() ? resultB : resultA;
        JsonNode loserBody = objectMapper.readTree(loser.getBody());
        assertThat(loserBody.get("detail").asText())
                .isEqualTo("Booking status was already changed by another request; reload and retry.");
    }

    private String createBooking() throws Exception {
        HttpHeaders headers = authHeaders("estudiante-token");
        String body = """
                {"resourceId":"%s","requestedStart":"2026-09-15T10:00:00Z","requestedEnd":"2026-09-15T12:00:00Z","notes":null}
                """.formatted(RESOURCE_ID);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        JsonNode json = objectMapper.readTree(response.getBody());
        return json.get("id").asText();
    }

    private ResponseEntity<String> put(String id, String body) {
        HttpHeaders headers = authHeaders("tecnico-token");
        return restTemplate.exchange(
                "/api/bookings/" + id + "/status", HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
                .claim("iss", "http://localhost:8081/realms/campuslab")
                .claim("realm_access", Map.of("roles", roles))
                .build();
    }
}
