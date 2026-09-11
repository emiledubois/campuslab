package cl.campuslab.kafkaadmin.kafka;

/**
 * Response shape for {@code GET /api/admin/kafka/topics} (design doc §3) - {@code
 * configMatches} is the concrete meaning of "crea y verifica" for Kafka (design doc
 * §5.1): {@code false} means a genuine drift between what kafka-admin expects and what
 * the broker actually has for this topic, surfaced here rather than silently accepted.
 */
public record TopicInfo(
        String name,
        int expectedPartitions,
        int actualPartitions,
        int expectedReplicationFactor,
        int actualReplicationFactor,
        String expectedCleanupPolicy,
        String actualCleanupPolicy,
        long expectedRetentionMs,
        Long actualRetentionMs,
        boolean configMatches) {
}
