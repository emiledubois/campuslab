package cl.campuslab.bookings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.bookings.AbstractIntegrationTest;
import cl.campuslab.bookings.catalog.StubCatalogServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The approval saga's own acceptance criteria (design doc §9 AC1/AC2/AC3/AC4/AC6/AC7/
 * AC9), against a real Postgres (Testcontainers) for bookings and a
 * {@link StubCatalogServer} standing in for ms-campuslab-catalog (§10 Open Question 3 -
 * a full two-real-service harness with catalog's own Postgres is judged out of scope for
 * this slice's test infrastructure; catalog's own atomic-decrement race is proven
 * separately, against real Postgres, by ConcurrentDecrementRaceTest in
 * ms-campuslab-catalog itself). Real HTTP, real threads where concurrency matters (AC3),
 * real timeouts (AC5's own dedicated class, AC6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ApprovalSagaApiTest extends AbstractIntegrationTest {

    private static final StubCatalogServer STUB_CATALOG = new StubCatalogServer();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private ExecutorService executor;

    @DynamicPropertySource
    static void catalogProperties(DynamicPropertyRegistry registry) {
        registry.add("catalog.service-url", STUB_CATALOG::baseUrl);
    }

    @BeforeEach
    void stubTokens() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void approve_withAvailableStock_returns200AndDecrementsExactlyOnce() throws Exception {
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 5);
        String id = createBooking(resourceId);
        int callsBefore = STUB_CATALOG.decrementCallCount();

        putStatus(id, "APROBADA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADA"));
        assertThat(STUB_CATALOG.decrementCallCount() - callsBefore).isEqualTo(1);
    }

    @Test
    void approve_withZeroStock_returns409AndBookingStaysSolicitada() throws Exception {
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 0);
        String id = createBooking(resourceId);

        putStatus(id, "APROBADA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Cannot approve this booking: the referenced resource has no stock/cupo available."));

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLICITADA"));
    }

    @Test
    void approve_withCatalogResourceNotFound_returns409NotFourOhFour() throws Exception {
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setNotFound(resourceId);
        String id = createBooking(resourceId);

        putStatus(id, "APROBADA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Cannot approve this booking: the referenced catalog resource does not exist."));

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLICITADA"));
    }

    @Test
    void approve_retriedAfterPerceivedTimeout_succeedsAndDecrementsExactlyOnceNetTotal() throws Exception {
        // AC6: the first decrement call commits server-side but its response is delayed
        // past bookings' configured read timeout (test yml: 300ms) - bookings treats this
        // as a failure (503, booking stays SOLICITADA). A fresh PUT retries the same
        // booking's approval; the ledger (simulated in the stub the same way catalog's
        // real one works) makes the retry idempotent, so net stock change is exactly -1.
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 5);
        STUB_CATALOG.delayFirstDecrementBy(resourceId, 1500);
        String id = createBooking(resourceId);
        int callsBefore = STUB_CATALOG.decrementCallCount();

        putStatus(id, "APROBADA").andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(jsonPath("$.status").value("SOLICITADA"));

        putStatus(id, "APROBADA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROBADA"));

        assertThat(STUB_CATALOG.decrementCallCount() - callsBefore).isEqualTo(2);
        assertThat(STUB_CATALOG.getStock(resourceId)).isEqualTo(4);
    }

    @Test
    void approve_concurrentDuplicateApprovalRace_exactlyOneSucceedsAndStockNetsMinusOneWithNoCompensation()
            throws Exception {
        // AC7a (Revision 1): two concurrent PUTs on the SAME booking both call catalog's
        // decrement for the identical (resourceId, bookingId), both succeed against the
        // stub, and the outcome is decided by bookings' own local optimistic lock. The
        // loser's re-read finds the booking already APROBADA (the winner's duplicate
        // approval) and correctly skips compensation - net stock change is exactly -1,
        // matching the one booking that actually reached APROBADA, not 0.
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 5);
        String id = createBooking(resourceId);
        int decrementCallsBefore = STUB_CATALOG.decrementCallCount();
        int incrementCallsBefore = STUB_CATALOG.incrementCallCount();
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<MvcResult> raceA = () -> {
            startLatch.await();
            return putStatus(id, "APROBADA").andReturn();
        };
        Callable<MvcResult> raceB = () -> {
            startLatch.await();
            return putStatus(id, "APROBADA").andReturn();
        };

        Future<MvcResult> futureA = executor.submit(raceA);
        Future<MvcResult> futureB = executor.submit(raceB);
        startLatch.countDown();
        MvcResult resultA = futureA.get(30, TimeUnit.SECONDS);
        MvcResult resultB = futureB.get(30, TimeUnit.SECONDS);

        List<HttpStatus> statuses = List.of(
                HttpStatus.valueOf(resultA.getResponse().getStatus()),
                HttpStatus.valueOf(resultB.getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(STUB_CATALOG.decrementCallCount() - decrementCallsBefore).isEqualTo(2);
        // Revision 1: the compensating increment must NEVER be invoked for this race -
        // the one physical decrement (the stub's ledger collapses the second decrement
        // into a replay, same idempotency key as real catalog's) legitimately belongs to
        // the booking that actually reached APROBADA.
        assertThat(STUB_CATALOG.incrementCallCount() - incrementCallsBefore).isEqualTo(0);
        assertThat(STUB_CATALOG.getStock(resourceId)).isEqualTo(4);

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(jsonPath("$.status").value("APROBADA"));
    }

    @Test
    void approve_thenFullLegalChain_doesNotTouchCatalogAgain() throws Exception {
        // AC9's regression check: only the initial APROBADA step touches catalog; the
        // remaining transitions must not call decrement/increment at all.
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 5);
        String id = createBooking(resourceId);

        putStatus(id, "APROBADA").andExpect(status().isOk());
        int callsAfterApproval = STUB_CATALOG.decrementCallCount() + STUB_CATALOG.incrementCallCount();

        putStatus(id, "EN_PREPARACION").andExpect(status().isOk());
        putStatus(id, "EN_USO").andExpect(status().isOk());
        putStatus(id, "DEVUELTA").andExpect(status().isOk());

        assertThat(STUB_CATALOG.decrementCallCount() + STUB_CATALOG.incrementCallCount()).isEqualTo(callsAfterApproval);
    }

    @Test
    void approve_racingConcurrentCancelBySameStudent_compensatesAndRestoresStockToPreDecrementValue()
            throws Exception {
        // AC7b, real end-to-end closure of the reviewer's MINOR finding (design doc §9
        // AC7b): a genuinely different concurrent transition (a student's own cancel)
        // wins the LOCAL race against a concurrent approval, real threads, real HTTP,
        // against the StubCatalogServer (not a mocked Java interface this time) - proving
        // the compensating increment is actually invoked over the wire and the stub's
        // observed stock genuinely returns to its pre-decrement value, not merely that
        // bookings' disambiguation branch calls a mock. The decrement is delayed by 150ms
        // (well under this test config's 300ms read timeout, so it still returns SUCCESS)
        // to give the cancel's local-only write - no network call at all - a realistic,
        // large head start to land first and change the booking's version before the
        // approval's own saveAndFlush is attempted.
        UUID resourceId = UUID.randomUUID();
        STUB_CATALOG.setStock(resourceId, 5);
        STUB_CATALOG.delayFirstDecrementBy(resourceId, 150);
        String id = createBooking(resourceId);
        int incrementCallsBefore = STUB_CATALOG.incrementCallCount();
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<MvcResult> approveRace = () -> {
            startLatch.await();
            return putStatus(id, "APROBADA").andReturn();
        };
        Callable<MvcResult> cancelRace = () -> {
            startLatch.await();
            return putStatusAs(id, "CANCELADA", "estudiante-token").andReturn();
        };

        Future<MvcResult> approveFuture = executor.submit(approveRace);
        Future<MvcResult> cancelFuture = executor.submit(cancelRace);
        startLatch.countDown();
        MvcResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        MvcResult cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);

        // The cancel, having no network call to wait on, is expected to win the local
        // race against the artificially delayed approval every time under this timing
        // margin - asserted, not assumed, so a genuine flake would show up as a failure
        // here rather than silently passing on the wrong branch.
        assertThat(cancelResult.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(approveResult.getResponse().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());

        mockMvc.perform(get("/api/bookings/" + id).header("Authorization", "Bearer tecnico-token"))
                .andExpect(jsonPath("$.status").value("CANCELADA"));

        // The compensating increment must have been invoked exactly once, and the stub's
        // observed stock must be back at its pre-decrement value (5) - net 0, proven via
        // a real HTTP round trip to the stub, not a mocked verify().
        assertThat(STUB_CATALOG.incrementCallCount() - incrementCallsBefore).isEqualTo(1);
        assertThat(STUB_CATALOG.getStock(resourceId)).isEqualTo(5);
    }

    private String createBooking(UUID resourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceId":"%s","requestedStart":"2026-09-15T10:00:00Z","requestedEnd":"2026-09-15T12:00:00Z","notes":null}
                                """.formatted(resourceId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private ResultActions putStatus(String id, String status) throws Exception {
        return putStatusAs(id, status, "tecnico-token");
    }

    private ResultActions putStatusAs(String id, String status, String token) throws Exception {
        return mockMvc.perform(put("/api/bookings/" + id + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"%s"}
                        """.formatted(status)));
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
