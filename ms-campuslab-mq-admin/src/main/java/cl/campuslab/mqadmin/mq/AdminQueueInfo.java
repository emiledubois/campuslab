package cl.campuslab.mqadmin.mq;

/** Response for queue create/get (Slice A design doc §3.2/§3.10) - server-constructed,
 * never bound from client input, so no Bean Validation here. */
public record AdminQueueInfo(
        String name, boolean durable, boolean exclusive, boolean autoDelete, int messageCount, int consumerCount) {
}
