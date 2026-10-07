package cl.campuslab.mqadmin.mq;

/** Response for {@code POST /api/admin/mq/queues/{name}/purge} (Slice A design doc §3.4). */
public record PurgeResult(String queueName, int purgedMessageCount) {
}
