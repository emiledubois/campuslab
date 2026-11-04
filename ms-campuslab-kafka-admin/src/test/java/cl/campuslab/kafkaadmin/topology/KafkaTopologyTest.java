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

    @Test
    void isManagedTopic_verdaderoParaLosCincoYFalsoParaUnoAdHoc() {
        // Arrange
        List<String> managed = KafkaTopology.ALL_TOPICS.stream().map(TopicSpec::name).toList();

        // Act + Assert
        assertThat(managed).hasSize(5).allSatisfy(name -> assertThat(KafkaTopology.isManagedTopic(name)).isTrue());
        assertThat(KafkaTopology.isManagedTopic("demo.ops.topic")).isFalse();
        assertThat(KafkaTopology.isManagedTopic(null)).isFalse();
    }

    @Test
    void collidingManagedTopic_detectaColisionPuntoGuionBajoPeroNoElNombreExacto() {
        // Act
        Optional<String> colliding = KafkaTopology.collidingManagedTopic("bookings_events");
        Optional<String> exact = KafkaTopology.collidingManagedTopic("bookings.events");
        Optional<String> unrelated = KafkaTopology.collidingManagedTopic("demo.ops.topic");

        // Assert
        assertThat(colliding).contains("bookings.events");
        assertThat(exact).isEmpty();
        assertThat(unrelated).isEmpty();
    }

    @Test
    void managedConsumerGroups_contieneLosTresGruposDelSistema() {
        // Act
        List<String> groups = KafkaTopology.MANAGED_CONSUMER_GROUPS;

        // Assert
        assertThat(groups).containsExactlyInAnyOrder("audit-service", "report-service", "report-timeline-projection");
        assertThat(KafkaTopology.isManagedConsumerGroup("audit-service")).isTrue();
        assertThat(KafkaTopology.isManagedConsumerGroup("demo.ops.group")).isFalse();
    }

    @Test
    void dltNames_elGrupoDeCadaDltEstaEnManagedConsumerGroups() {
        // Arrange
        List<String> baseTopics = List.of(KafkaTopology.TOPIC_BOOKINGS_EVENTS, KafkaTopology.TOPIC_AUDIT_TIMELINE);

        // Act
        List<String> groupsInDlts = KafkaTopology.DLT_NAMES.stream()
                .map(dlt -> {
                    String base = baseTopics.stream().filter(topic -> dlt.startsWith(topic + ".")).findFirst().orElseThrow();
                    return dlt.substring(base.length() + 1, dlt.length() - ".DLT".length());
                })
                .toList();

        // Assert
        assertThat(KafkaTopology.MANAGED_CONSUMER_GROUPS).containsAll(groupsInDlts);
    }

    private static Optional<TopicSpec> find(String name) {
        return KafkaTopology.ALL_TOPICS.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }
}
