package cl.campuslab.kafkaadmin.kafka;

import java.util.List;

/**
 * Response shape for {@code GET /api/admin/kafka/consumer-groups} (design doc §3) -
 * one entry per consumer group AdminClient reports on the broker (one this slice,
 * {@code audit-service}; a future {@code report-service} group, slice 7, is additive -
 * see design doc §10 open question 5).
 */
public record ConsumerGroupInfo(String groupId, String state, List<TopicLag> topics) {
}
