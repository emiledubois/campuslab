package cl.campuslab.kafkaadmin.topology;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.kafkaadmin.topology.KafkaTopology.TopicSpec;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/**
 * audit-timeline-compacted.md §9 AC 15: la topologia declarada incluye el DLT nuevo de la
 * proyeccion de report y no cambia la configuracion de audit.timeline.
 */
class KafkaTopologyTest {

    @Test
    void allTopics_containsTimelineProjectionDltWithExpectedSpec() {
        // Arrange
        String dlt = "audit.timeline.report-timeline-projection.DLT";

        // Act
        Optional<TopicSpec> spec = find(dlt);

        // Assert
        assertThat(spec).isPresent();
        assertThat(spec.get().partitions()).isEqualTo(3);
        assertThat(spec.get().cleanupPolicy()).isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
        assertThat(spec.get().retentionMs()).isEqualTo(864_000_000L);
        assertThat(KafkaTopology.DLT_AUDIT_TIMELINE_REPORT_TIMELINE_PROJECTION).isEqualTo(dlt);
    }

    @Test
    void dltNames_containsTheThreeDlts() {
        // Arrange
        List<String> expected = List.of(
                "bookings.events.audit-service.DLT",
                "bookings.events.report-service.DLT",
                "audit.timeline.report-timeline-projection.DLT");

        // Act
        List<String> dltNames = KafkaTopology.DLT_NAMES;

        // Assert
        assertThat(dltNames).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void dltNames_everyDltIsDeclaredAndFollowsTopicGroupDltConvention() {
        // Arrange
        List<String> baseTopics = List.of(KafkaTopology.TOPIC_BOOKINGS_EVENTS, KafkaTopology.TOPIC_AUDIT_TIMELINE);

        // Act
        List<String> dltNames = KafkaTopology.DLT_NAMES;

        // Assert
        assertThat(dltNames).allSatisfy(dlt -> {
            assertThat(find(dlt)).isPresent();
            assertThat(dlt).endsWith(".DLT");
            assertThat(baseTopics).anySatisfy(topic -> {
                assertThat(dlt).startsWith(topic + ".");
                String group = dlt.substring(topic.length() + 1, dlt.length() - ".DLT".length());
                assertThat(group).isNotBlank().doesNotContain(".");
            });
        });
    }

    @Test
    void allTopics_auditTimelineKeepsCompactDeleteAndTwentyOneDays() {
        // Arrange
        String topic = KafkaTopology.TOPIC_AUDIT_TIMELINE;

        // Act
        TopicSpec spec = find(topic).orElseThrow();

        // Assert
        assertThat(spec.partitions()).isEqualTo(3);
        assertThat(spec.cleanupPolicy()).isEqualTo("compact,delete");
        assertThat(spec.retentionMs()).isEqualTo(1_814_400_000L);
    }

    private static Optional<TopicSpec> find(String name) {
        return KafkaTopology.ALL_TOPICS.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }
}
