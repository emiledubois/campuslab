package cl.campuslab.kafkaadmin.kafka;

/**
 * Response shape for {@code GET /api/admin/kafka/dlt} (design doc §3). {@code
 * approximateMessageCount} is {@code latestOffset - earliestOffset} summed across
 * partitions - an approximation of currently-retained messages, not a consumed-vs-
 * produced lag figure (a DLT has no "expected consumer" to lag against).
 */
public record DltInfo(String name, long approximateMessageCount) {
}
