package cl.campuslab.mqadmin.mq;

/**
 * Response shape for {@code GET /api/admin/mq/queues} (design doc §3) - {@code
 * dlqRatePerMinute} is {@code null} for a work queue (rate only makes sense for a DLQ)
 * and a sampled, possibly-zero approximation for a DLQ (design doc §7 A06/§10 open
 * question 1), never a RabbitMQ-computed statistic.
 */
public record QueueInfo(String name, boolean isDlq, long messageCount, int consumerCount, Double dlqRatePerMinute) {
}
