package cl.campuslab.kafkaadmin.topology;

import java.util.List;
import java.util.Optional;
import org.apache.kafka.common.config.TopicConfig;

/**
 * Single source of truth for every topic this slice's kafka-admin owns (design doc
 * §5.1's table) - the exact partition count/cleanup policy/retention this project
 * commits to, identical across local/deploy profiles (only replication factor differs,
 * read separately from {@link KafkaAdminProperties}). Referenced by both the startup
 * creation/verification logic and the inspection endpoints, so the two never drift
 * apart on a literal.
 */
public final class KafkaTopology {

    public static final String TOPIC_BOOKINGS_EVENTS = "bookings.events";
    public static final String TOPIC_AUDIT_TIMELINE = "audit.timeline";

    /** DLT naming convention, decided explicitly (design doc §5.1): {@code
     * <topic>.<consumer-group-id>.DLT}. Audit's consumer group is {@code audit-service}. */
    public static final String DLT_BOOKINGS_EVENTS_AUDIT_SERVICE = "bookings.events.audit-service.DLT";

    /** Report's consumer group is {@code report-service} (reporting.md §5.1). */
    public static final String DLT_BOOKINGS_EVENTS_REPORT_SERVICE = "bookings.events.report-service.DLT";

    /** Group de la proyeccion de report sobre audit.timeline (audit-timeline-compacted.md §5.6). */
    public static final String DLT_AUDIT_TIMELINE_REPORT_TIMELINE_PROJECTION = "audit.timeline.report-timeline-projection.DLT";

    public static final List<TopicSpec> ALL_TOPICS = List.of(
            new TopicSpec(TOPIC_BOOKINGS_EVENTS, 3, TopicConfig.CLEANUP_POLICY_DELETE, 432_000_000L),
            new TopicSpec(TOPIC_AUDIT_TIMELINE, 3, "compact,delete", 1_814_400_000L),
            new TopicSpec(DLT_BOOKINGS_EVENTS_AUDIT_SERVICE, 3, TopicConfig.CLEANUP_POLICY_DELETE, 864_000_000L),
            new TopicSpec(DLT_BOOKINGS_EVENTS_REPORT_SERVICE, 3, TopicConfig.CLEANUP_POLICY_DELETE, 864_000_000L),
            new TopicSpec(DLT_AUDIT_TIMELINE_REPORT_TIMELINE_PROJECTION, 3, TopicConfig.CLEANUP_POLICY_DELETE, 864_000_000L));

    public static final List<String> DLT_NAMES = List.of(
            DLT_BOOKINGS_EVENTS_AUDIT_SERVICE,
            DLT_BOOKINGS_EVENTS_REPORT_SERVICE,
            DLT_AUDIT_TIMELINE_REPORT_TIMELINE_PROJECTION);

    /** Consumer groups del sistema (kafka-admin-write.md §5.1). Literales duplicados a
     * proposito: kafka-admin no puede importar clases de audit/report. */
    public static final String GROUP_AUDIT_SERVICE = "audit-service";
    public static final String GROUP_REPORT_SERVICE = "report-service";
    public static final String GROUP_REPORT_TIMELINE_PROJECTION = "report-timeline-projection";

    public static final List<String> MANAGED_CONSUMER_GROUPS =
            List.of(GROUP_AUDIT_SERVICE, GROUP_REPORT_SERVICE, GROUP_REPORT_TIMELINE_PROJECTION);

    private KafkaTopology() {
    }

    public static boolean isManagedTopic(String name) {
        return name != null && ALL_TOPICS.stream().anyMatch(spec -> spec.name().equals(name));
    }

    /** Kafka trata '.' y '_' como equivalentes en nombres de metricas, por lo que
     * 'bookings_events' colisiona con 'bookings.events' aunque no sea el mismo nombre. */
    public static Optional<String> collidingManagedTopic(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.replace('.', '_');
        return ALL_TOPICS.stream()
                .map(TopicSpec::name)
                .filter(managed -> !managed.equals(name))
                .filter(managed -> managed.replace('.', '_').equals(normalized))
                .findFirst();
    }

    public static boolean isManagedConsumerGroup(String groupId) {
        return groupId != null && MANAGED_CONSUMER_GROUPS.contains(groupId);
    }

    public record TopicSpec(String name, int partitions, String cleanupPolicy, long retentionMs) {
    }
}
