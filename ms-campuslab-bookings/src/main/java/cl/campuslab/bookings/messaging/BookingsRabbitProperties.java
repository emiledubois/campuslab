package cl.campuslab.bookings.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No default value on any field, deliberately (design doc
 * mq-names-domain-separation.md §9 AC11) - a missing {@code application.yml} key must
 * fail loudly at context startup / first publish, never silently fall back to a value
 * that could drift from {@code ms-campuslab-mq-admin}'s {@code MqTopology}, the only
 * service allowed to declare RabbitMQ topology. Values here must equal
 * {@code MqTopology.EXCHANGE_CMD_DIRECT}/{@code WORK_QUEUE_DIRECT_ROUTING_KEY} exactly -
 * a mismatch here does not fail fast, it silently breaks routing (see design doc
 * mq-names-domain-separation.md §10).
 */
@ConfigurationProperties(prefix = "messaging.rabbit")
public record BookingsRabbitProperties(String exchange, RoutingKey routingKey) {

    public record RoutingKey(String email, String prep) {
    }
}
