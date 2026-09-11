package cl.campuslab.kafkaadmin.topology;

import java.util.List;
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

    public static final List<TopicSpec> ALL_TOPICS = List.of(
            new TopicSpec(TOPIC_BOOKINGS_EVENTS, 3, TopicConfig.CLEANUP_POLICY_DELETE, 432_000_000L),
            new TopicSpec(TOPIC_AUDIT_TIMELINE, 3, "compact,delete", 1_814_400_000L),
            new TopicSpec(DLT_BOOKINGS_EVENTS_AUDIT_SERVICE, 3, TopicConfig.CLEANUP_POLICY_DELETE, 864_000_000L),
            new TopicSpec(DLT_BOOKINGS_EVENTS_REPORT_SERVICE, 3, TopicConfig.CLEANUP_POLICY_DELETE, 864_000_000L));

    public static final List<String> DLT_NAMES =
            List.of(DLT_BOOKINGS_EVENTS_AUDIT_SERVICE, DLT_BOOKINGS_EVENTS_REPORT_SERVICE);

    private KafkaTopology() {
    }

    public record TopicSpec(String name, int partitions, String cleanupPolicy, long retentionMs) {
    }
}
