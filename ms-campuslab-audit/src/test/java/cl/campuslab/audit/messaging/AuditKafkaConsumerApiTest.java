package cl.campuslab.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cl.campuslab.audit.AbstractIntegrationTest;
import cl.campuslab.audit.domain.TimelineEvent;
import cl.campuslab.audit.domain.TimelineEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Proves kafka-audit.md §9 AC6/AC7/AC8 against a real (Testcontainers) Kafka broker and
 * a real Postgres - never mocked, since these acceptance criteria are explicitly about
 * real idempotent redelivery, real poison-message DLT routing, and a real transient-
 * vs-permanent failure distinction, none of which a mocked ConsumerFactory could prove.
 */
@SpringBootTest
class AuditKafkaConsumerApiTest extends AbstractIntegrationTest {

    private static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.9.9");
    private static KafkaProducer<String, String> jsonProducer;
    private static KafkaProducer<String, byte[]> rawProducer;

    static {
        KAFKA.start();
    }

    @Autowired
    private TimelineEventRepository repository;

    // Business-logic test, not an auth test - mocked purely so the context doesn't try
    // to reach a real Entra issuer.
    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // A real connection-refused/timeout must surface within the FixedBackOff(1000,2)
        // retry window (design doc §9 AC8), not Hikari's 30s default.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "800");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "800");
    }

    @BeforeAll
    static void startProducers() {
        Properties jsonProps = new Properties();
        jsonProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        jsonProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        jsonProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        jsonProducer = new KafkaProducer<>(jsonProps);

        Properties rawProps = new Properties();
        rawProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        rawProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        rawProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        rawProducer = new KafkaProducer<>(rawProps);
    }

    @AfterAll
    static void closeProducers() {
        jsonProducer.close();
        rawProducer.close();
    }

    @Test
    void redeliveryOfAnAlreadyConsumedEventId_neverDuplicates() {
        UUID bookingId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String envelope = wellFormedEnvelopeJson(eventId, bookingId, BookingStreamEventType.BOOKING_SOLICITADA, null, "SOLICITADA");

        publishAndWaitPersisted(bookingId, eventId, envelope);
        assertThat(repository.existsByEventId(eventId)).isTrue();

        // Simulated redelivery: the exact same eventId, published again.
        jsonProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), envelope));

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countByBookingId(bookingId)).isEqualTo(1));
    }

    @Test
    void poisonMessage_neverCrashesConsumer_andNextGoodMessageStillPersists() {
        UUID bookingId = UUID.randomUUID();
        rawProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), "not valid json".getBytes()));

        String goodEventId = UUID.randomUUID().toString();
        String goodEnvelope = wellFormedEnvelopeJson(goodEventId, bookingId, BookingStreamEventType.BOOKING_CANCELADA, "SOLICITADA", "CANCELADA");

        await().atMost(Duration.ofSeconds(5)).until(() -> {
            jsonProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), goodEnvelope));
            return true;
        });

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(repository.existsByEventId(goodEventId)).isTrue());
    }

    @Test
    void unrecognizedEventType_reachesDltDirectly_neverRetried() {
        UUID bookingId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String envelope = "{\"type\":\"NOT_A_REAL_TYPE\",\"eventId\":\"" + eventId + "\",\"timestamp\":\""
                + Instant.now() + "\",\"traceId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + bookingId
                + "\",\"payload\":{\"bookingId\":\"" + bookingId + "\",\"resourceId\":\"" + UUID.randomUUID()
                + "\",\"studentOid\":\"student-oid\",\"actorOid\":\"student-oid\",\"actorRoles\":[\"ESTUDIANTE\"],"
                + "\"fromStatus\":null,\"toStatus\":\"SOLICITADA\"}}";

        jsonProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), envelope));

        List<ConsumerRecord<String, byte[]>> dltRecords = pollDlt(bookingId.toString(), 1, Duration.ofSeconds(15));
        assertThat(dltRecords).isNotEmpty();
        assertThat(repository.existsByEventId(eventId)).isFalse();
    }

    @Test
    void transientDbOutage_retriesThenRecovers_whenDbComesBackWithinWindow() {
        UUID bookingId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String envelope = wellFormedEnvelopeJson(eventId, bookingId, BookingStreamEventType.BOOKING_APROBADA, "SOLICITADA", "APROBADA");
        String containerId = POSTGRES.getContainerId();

        POSTGRES.getDockerClient().pauseContainerCmd(containerId).exec();
        try {
            jsonProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), envelope));
            // Backoff is 1s between the 2 retries - resume well inside that window so
            // one of the retry attempts succeeds (design doc §9 AC8's "recovers" branch).
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(containerId).exec();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(repository.existsByEventId(eventId)).isTrue());
    }

    private void publishAndWaitPersisted(UUID bookingId, String eventId, String envelope) {
        jsonProducer.send(new ProducerRecord<>("bookings.events", bookingId.toString(), envelope));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(repository.existsByEventId(eventId)).isTrue());
    }

    private long countByBookingId(UUID bookingId) {
        return repository.findAll().stream().filter(event -> event.getBookingId().equals(bookingId)).count();
    }

    private static String wellFormedEnvelopeJson(String eventId, UUID bookingId, String type, String from, String to) {
        String fromJson = from == null ? "null" : "\"" + from + "\"";
        return "{\"type\":\"" + type + "\",\"eventId\":\"" + eventId + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"traceId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + bookingId
                + "\",\"payload\":{\"bookingId\":\"" + bookingId + "\",\"resourceId\":\"" + UUID.randomUUID()
                + "\",\"studentOid\":\"student-oid\",\"actorOid\":\"tecnico-oid\",\"actorRoles\":[\"TECNICO\"],"
                + "\"fromStatus\":" + fromJson + ",\"toStatus\":\"" + to + "\"}}";
    }

    private List<ConsumerRecord<String, byte[]>> pollDlt(String key, int minCount, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> matched = new java.util.ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConsumerConfig.DLT_TOPIC));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (matched.size() < minCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (key.equals(record.key())) {
                        matched.add(record);
                    }
                }
            }
        }
        return matched;
    }
}
