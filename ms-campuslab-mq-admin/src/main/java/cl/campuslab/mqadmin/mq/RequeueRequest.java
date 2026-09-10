package cl.campuslab.mqadmin.mq;

/**
 * Body for {@code POST /api/admin/mq/dlq/{dlqName}/requeue} (design doc §3) - exactly one
 * of the two fields is valid; the mutual-exclusivity/positivity check is done in
 * {@link RequeueService}, not via Bean Validation annotations, since the rule spans both
 * fields together rather than constraining either one in isolation.
 */
public record RequeueRequest(Integer count, Boolean all) {
}
