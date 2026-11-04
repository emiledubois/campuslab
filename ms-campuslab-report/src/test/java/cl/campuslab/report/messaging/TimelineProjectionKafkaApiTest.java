package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cl.campuslab.report.AbstractIntegrationTest;
import cl.campuslab.report.domain.BookingStateProjection;
import cl.campuslab.report.domain.BookingStateProjectionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * audit-timeline-compacted.md §9 AC 13 contra Kafka y Postgres reales (Testcontainers): el
 * contrato de {@code upsertIfNewer}, la proyeccion desde {@code audit.timeline}, el DLT
 * propio del group {@code report-timeline-projection} y la reconstruccion con tabla vacia.
 */
@SpringBootTest
class TimelineProjectionKafkaApiTest extends AbstractIntegrationTest {

    private static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.9.9");
    private static final String TOPIC = "audit.timeline";
    private static KafkaProducer<String, String> jsonProducer;
    private static KafkaProducer<String, byte[]> rawProducer;

    static {
        KAFKA.start();
    }

    @Autowired
    private BookingStateProjectionRepository repository;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // Test de logica de negocio, no de autenticacion: evita llamar a un issuer real.
    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void createTopicsAndProducers() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(adminProps)) {
            NewTopic timeline = new NewTopic(TOPIC, 3, (short) 1).configs(Map.of(
                    TopicConfig.CLEANUP_POLICY_CONFIG, "compact,delete",
                    TopicConfig.RETENTION_MS_CONFIG, "1814400000"));
            NewTopic dlt = new NewTopic(TimelineProjectionKafkaConfig.DLT_TOPIC_TIMELINE_PROJECTION, 3, (short) 1)
                    .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                            TopicConfig.RETENTION_MS_CONFIG, "864000000"));
            admin.createTopics(List.of(timeline, dlt)).all().get(10, TimeUnit.SECONDS);
        }

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
    void upsertIfNewer_againstRealPostgres_insertsUpdatesAndIgnoresOlderOrEqual() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t2 = t1.plusSeconds(10);
        Instant t0 = t1.minusSeconds(10);

        // Act
        int inserted = upsert(bookingId, resourceId, "APROBADA", t1, "e-ins-" + bookingId);
        int newer = upsert(bookingId, resourceId, "EN_PREPARACION", t2, "e-new-" + bookingId);
        int older = upsert(bookingId, resourceId, "SOLICITADA", t0, "e-old-" + bookingId);
        int equal = upsert(bookingId, resourceId, "CANCELADA", t2, "e-eq-" + bookingId);

        // Assert
        assertThat(inserted).isEqualTo(1);
        assertThat(newer).isEqualTo(1);
        assertThat(older).isZero();
        assertThat(equal).isZero();
        BookingStateProjection row = repository.findById(bookingId).orElseThrow();
        assertThat(row.getCurrentStatus()).isEqualTo("EN_PREPARACION");
        assertThat(row.getLastEventId()).isEqualTo("e-new-" + bookingId);
        assertThat(row.getLastTransitionAt()).isEqualTo(t2);
    }

    @Test
    void snapshotPublished_appliesRow_duplicateLeavesItUnchanged_staleDoesNotChangeIt() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String eventId = UUID.randomUUID().toString();
        String current = snapshotJson(eventId, bookingId, resourceId, "EN_USO", t2);
        String stale = snapshotJson(UUID.randomUUID().toString(), bookingId, resourceId, "APROBADA", t2.minusSeconds(60));

        // Act
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingId.toString(), current));
        await().atMost(Duration.ofSeconds(30)).until(() -> repository.findById(bookingId).isPresent());
        Instant firstUpdatedAt = repository.findById(bookingId).orElseThrow().getUpdatedAt();
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingId.toString(), current));
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingId.toString(), stale));
        UUID markerBooking = sendMarker();

        // Assert
        await().atMost(Duration.ofSeconds(20)).until(() -> repository.findById(markerBooking).isPresent());
        BookingStateProjection row = repository.findById(bookingId).orElseThrow();
        assertThat(row.getCurrentStatus()).isEqualTo("EN_USO");
        assertThat(row.getLastEventId()).isEqualTo(eventId);
        assertThat(row.getUpdatedAt()).isEqualTo(firstUpdatedAt);
        assertThat(repository.findAll().stream().filter(r -> r.getBookingId().equals(bookingId)).count()).isEqualTo(1);
    }

    @Test
    void poisonRecords_reachProjectionDlt_andNextValidSnapshotIsApplied() {
        // Arrange
        UUID nonJsonKey = UUID.randomUUID();
        UUID foreignTypeBooking = UUID.randomUUID();
        UUID mismatchBooking = UUID.randomUUID();
        String foreignType = snapshotJson(UUID.randomUUID().toString(), foreignTypeBooking, UUID.randomUUID(),
                "APROBADA", Instant.now()).replace("BOOKING_STATE_SNAPSHOT", "BOOKING_APROBADA");
        String mismatch = snapshotJson(UUID.randomUUID().toString(), mismatchBooking, UUID.randomUUID(),
                "APROBADA", Instant.now());
        String mismatchKey = UUID.randomUUID().toString();
        UUID warmUp = sendMarker();
        await().atMost(Duration.ofSeconds(30)).until(() -> repository.findById(warmUp).isPresent());
        long start = System.currentTimeMillis();

        // Act
        rawProducer.send(new ProducerRecord<>(TOPIC, nonJsonKey.toString(), "not valid json".getBytes(StandardCharsets.UTF_8)));
        jsonProducer.send(new ProducerRecord<>(TOPIC, foreignTypeBooking.toString(), foreignType));
        jsonProducer.send(new ProducerRecord<>(TOPIC, mismatchKey, mismatch));
        UUID validAfter = sendMarker();

        // Assert
        await().atMost(Duration.ofSeconds(30)).until(() -> repository.findById(validAfter).isPresent());
        long elapsedMs = System.currentTimeMillis() - start;
        assertDeadLettered(nonJsonKey.toString(), "DeserializationException");
        assertDeadLettered(foreignTypeBooking.toString(), "UnrecognizedEventTypeException");
        assertDeadLettered(mismatchKey, "UnparseableEnvelopeException");
        assertThat(repository.findById(foreignTypeBooking)).isEmpty();
        assertThat(repository.findById(mismatchBooking)).isEmpty();
        // El backoff (1 s x 2) bloquea el hilo del consumidor: si los tres poison se
        // reintentaran, el registro valido posterior tardaria al menos 6 s en aplicarse.
        assertThat(elapsedMs).isLessThan(4_000L);
    }

    @Test
    void emptyProjectionOnRestart_rebuildsFromBeginningOfCompactedTopic() {
        // Arrange
        UUID bookingA = UUID.randomUUID();
        UUID bookingB = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingA.toString(),
                snapshotJson(UUID.randomUUID().toString(), bookingA, UUID.randomUUID(), "APROBADA", now)));
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingB.toString(),
                snapshotJson(UUID.randomUUID().toString(), bookingB, UUID.randomUUID(), "EN_USO", now)));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                repository.findById(bookingA).isPresent() && repository.findById(bookingB).isPresent());
        MessageListenerContainer container = registry.getListenerContainer("timelineProjection");

        // Act
        repository.deleteAll();
        container.stop();
        if (!container.isRunning()) {
            container.start();
        }

        // Assert
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(repository.findById(bookingA).map(BookingStateProjection::getCurrentStatus)).contains("APROBADA");
            assertThat(repository.findById(bookingB).map(BookingStateProjection::getCurrentStatus)).contains("EN_USO");
        });
    }

    // QA (audit-timeline-compacted.md §5.4.4/§5.6, AC 5): el group efectivo del container
    // debe ser report-timeline-projection, no el id del listener.
    @Test
    void timelineProjectionContainer_effectiveGroupId_isReportTimelineProjection() {
        // Arrange
        MessageListenerContainer container = registry.getListenerContainer("timelineProjection");

        // Act
        String groupId = container.getGroupId();

        // Assert
        assertThat(groupId).isEqualTo("report-timeline-projection");
    }

    private int upsert(UUID bookingId, UUID resourceId, String status, Instant at, String eventId) {
        Integer result = transactionTemplate.execute(tx -> repository.upsertIfNewer(bookingId, resourceId, status, at, eventId));
        return result == null ? -1 : result;
    }

    private UUID sendMarker() {
        UUID bookingId = UUID.randomUUID();
        jsonProducer.send(new ProducerRecord<>(TOPIC, bookingId.toString(),
                snapshotJson(UUID.randomUUID().toString(), bookingId, UUID.randomUUID(), "SOLICITADA", Instant.now())));
        return bookingId;
    }

    private static String snapshotJson(String eventId, UUID bookingId, UUID resourceId, String status, Instant at) {
        return "{\"type\":\"BOOKING_STATE_SNAPSHOT\",\"eventId\":\"" + eventId + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"traceId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + bookingId
                + "\",\"payload\":{\"bookingId\":\"" + bookingId + "\",\"resourceId\":\"" + resourceId
                + "\",\"studentOid\":\"student-oid\",\"currentStatus\":\"" + status
                + "\",\"lastEventType\":\"BOOKING_X\",\"lastActorOid\":\"actor-oid\",\"lastTransitionAt\":\"" + at
                + "\",\"sourceEventId\":\"" + UUID.randomUUID() + "\"}}";
    }

    private void assertDeadLettered(String key, String expectedExceptionSimpleName) {
        List<ConsumerRecord<String, byte[]>> records = pollDlt(key, Duration.ofSeconds(15));
        assertThat(records).hasSize(1);
        Optional<String> fqcn = header(records.get(0), "kafka_dlt-exception-fqcn");
        Optional<String> causeFqcn = header(records.get(0), "kafka_dlt-exception-cause-fqcn");
        assertThat(fqcn).isPresent();
        assertThat(fqcn.get() + "|" + causeFqcn.orElse("")).contains(expectedExceptionSimpleName);
    }

    private static Optional<String> header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? Optional.empty() : Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private List<ConsumerRecord<String, byte[]>> pollDlt(String key, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "projection-dlt-test-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TimelineProjectionKafkaConfig.DLT_TOPIC_TIMELINE_PROJECTION));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (matched.isEmpty() && System.currentTimeMillis() < deadline) {
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
