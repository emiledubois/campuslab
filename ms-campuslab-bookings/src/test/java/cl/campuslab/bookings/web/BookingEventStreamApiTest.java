package cl.campuslab.bookings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.campuslab.bookings.AbstractIntegrationTest;
import cl.campuslab.bookings.catalog.StubCatalogServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Proves kafka-audit.md §9 AC4/AC5 against a real (Testcontainers) Kafka broker, not a
 * mocked KafkaTemplate - a plain {@code KafkaConsumer} reads the actual {@code
 * bookings.events} topic bookings publishes to, the same way audit's own consumer would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BookingEventStreamApiTest extends AbstractIntegrationTest {

    private static final String RESOURCE_ID = "5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a99";
    private static final StubCatalogServer STUB_CATALOG = new StubCatalogServer();
    private static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.9.9");

    static {
        KAFKA.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private KafkaConsumer<String, String> consumer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.service-url", STUB_CATALOG::baseUrl);
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The test-default 300ms (src/test/resources/application.yml, tuned for a
        // deliberately-unreachable RFC 5737 address) is too tight for a real broker's
        // first metadata fetch/topic auto-creation - restore the production-sized bound
        // here so this test proves real delivery, not an artificially tight timeout.
        registry.add("kafka.request-timeout-ms", () -> "5000");
        registry.add("kafka.max-block-ms", () -> "5000");
    }

    @BeforeEach
    void stubTokensAndSubscribe() {
        given(jwtDecoder.decode("estudiante-token")).willReturn(jwt("estudiante-uuid", List.of("ESTUDIANTE")));
        given(jwtDecoder.decode("tecnico-token")).willReturn(jwt("tecnico-uuid", List.of("TECNICO")));

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("bookings.events"));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void fullLifecycle_publishesSixOrderedEventsWithCorrectActors() throws Exception {
        String id = createBooking("estudiante-token");
        approve(id);
        transition(id, "EN_PREPARACION");
        transition(id, "EN_USO");
        transition(id, "DEVUELTA");

        List<JsonNode> events = pollEventsFor(id, 5);

        assertThat(events).extracting(event -> event.get("type").asText()).containsExactly(
                "BOOKING_SOLICITADA", "BOOKING_APROBADA", "BOOKING_EN_PREPARACION", "BOOKING_EN_USO", "BOOKING_DEVUELTA");
        assertThat(events.get(0).get("payload").get("actorOid").asText()).isEqualTo("estudiante-uuid");
        assertThat(events.get(1).get("payload").get("actorOid").asText()).isEqualTo("tecnico-uuid");
        assertThat(events.get(4).get("payload").get("actorOid").asText()).isEqualTo("tecnico-uuid");
        assertThat(events).allSatisfy(event -> assertThat(event.get("correlationId").asText()).isEqualTo(id));
    }

    @Test
    void cancellingASolicitadaBooking_publishesCanceladaWithStudentAsActor() throws Exception {
        String id = createBooking("estudiante-token");

        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer estudiante-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELADA\"}"))
                .andExpect(status().isOk());

        List<JsonNode> events = pollEventsFor(id, 2);
        assertThat(events).extracting(event -> event.get("type").asText())
                .containsExactly("BOOKING_SOLICITADA", "BOOKING_CANCELADA");
        assertThat(events.get(1).get("payload").get("actorOid").asText()).isEqualTo("estudiante-uuid");
    }

    private String createBooking(String token) throws Exception {
        String body = "{\"resourceId\":\"" + RESOURCE_ID + "\",\"requestedStart\":\"2026-09-15T10:00:00Z\","
                + "\"requestedEnd\":\"2026-09-15T12:00:00Z\",\"notes\":null}";
        String response = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void approve(String id) throws Exception {
        transition(id, "APROBADA");
    }

    private void transition(String id, String status) throws Exception {
        mockMvc.perform(put("/api/bookings/" + id + "/status")
                        .header("Authorization", "Bearer tecnico-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(jsonPath("$.status").value(status));
    }

    private List<JsonNode> pollEventsFor(String bookingId, int expectedCount) throws Exception {
        List<JsonNode> matched = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 15000;
        while (matched.size() < expectedCount && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (bookingId.equals(record.key())) {
                    matched.add(objectMapper.readTree(record.value()));
                }
            }
        }
        matched.sort((a, b) -> a.get("timestamp").asText().compareTo(b.get("timestamp").asText()));
        return matched;
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .claim("oid", subject)
                .claim("iss", "https://login.microsoftonline.com/test-tenant/v2.0")
                .claim("roles", roles)
                .build();
    }
}
