package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * audit-timeline-compacted.md §9 AC 12: configuracion del container factory de la proyeccion
 * y deserializacion tolerante a campos extra del snapshot de audit.
 */
class TimelineProjectionKafkaConfigTest {

    @SuppressWarnings("unchecked")
    private ConcurrentKafkaListenerContainerFactory<String, Object> factory() {
        return new TimelineProjectionKafkaConfig().timelineProjectionKafkaListenerContainerFactory(
                new KafkaConsumerProperties("localhost:9092"), mock(KafkaTemplate.class), mock(KafkaTemplate.class));
    }

    @Test
    void containerFactory_consumerConfig_matchesDesign() {
        // Arrange
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = factory();

        // Act
        Map<String, Object> config = factory.getConsumerFactory().getConfigurationProperties();

        // Assert
        assertThat(config).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "report-timeline-projection");
        assertThat(config).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config).containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        assertThat(config).containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        assertThat(config).containsEntry(JsonDeserializer.VALUE_DEFAULT_TYPE, BookingStateSnapshotEnvelope.class.getName());
        assertThat(config).containsEntry(JsonDeserializer.TRUSTED_PACKAGES, "cl.campuslab.report.messaging");
    }

    @Test
    void containerFactory_containerProperties_recordAckAndNoAutoStartup() {
        // Arrange
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = factory();

        // Act
        ContainerProperties.AckMode ackMode = factory.getContainerProperties().getAckMode();

        // Assert
        assertThat(ackMode).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.createContainer("audit.timeline").isAutoStartup()).isFalse();
    }

    @Test
    void deserializer_snapshotWithExtraFieldsFromAudit_deserializesToReportDto() {
        // Arrange
        Map<String, Object> config = factory().getConsumerFactory().getConfigurationProperties();
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String json = "{\"type\":\"BOOKING_STATE_SNAPSHOT\",\"eventId\":\"e-1\",\"timestamp\":1762268531.123000000,"
                + "\"traceId\":\"t-1\",\"correlationId\":\"" + bookingId + "\",\"payload\":{\"bookingId\":\"" + bookingId
                + "\",\"resourceId\":\"" + resourceId + "\",\"studentOid\":\"student-oid\",\"currentStatus\":\"APROBADA\","
                + "\"lastEventType\":\"BOOKING_APROBADA\",\"lastActorOid\":\"tecnico-oid\","
                + "\"lastTransitionAt\":\"2026-11-04T15:02:10.987Z\",\"sourceEventId\":\"src-1\"}}";

        BookingStateSnapshotEnvelope envelope;
        try (JsonDeserializer<Object> deserializer = new JsonDeserializer<>()) {
            deserializer.configure(config, false);

            // Act
            envelope = (BookingStateSnapshotEnvelope) deserializer.deserialize(
                    "audit.timeline", json.getBytes(StandardCharsets.UTF_8));
        }

        // Assert
        assertThat(envelope.hasAllRequiredFields()).isTrue();
        assertThat(envelope.payload().bookingId()).isEqualTo(bookingId);
        assertThat(envelope.payload().resourceId()).isEqualTo(resourceId);
        assertThat(envelope.payload().currentStatus()).isEqualTo("APROBADA");
        assertThat(envelope.payload().lastTransitionAt()).isEqualTo(Instant.parse("2026-11-04T15:02:10.987Z"));
    }
}
