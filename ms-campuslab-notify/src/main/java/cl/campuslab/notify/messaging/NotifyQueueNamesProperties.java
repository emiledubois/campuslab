package cl.campuslab.notify.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No default value on either field, deliberately (design doc
 * mq-names-domain-separation.md §9 AC11) - a missing {@code application.yml} key must
 * fail loudly at context startup, never silently fall back to a value that could drift
 * from {@code ms-campuslab-mq-admin}'s {@code MqTopology}, the only service allowed to
 * declare RabbitMQ topology. Values here must equal {@code MqTopology.QUEUE_EMAIL}/
 * {@code QUEUE_PREP} exactly - a mismatch here does not fail fast, it silently breaks
 * routing (see design doc mq-names-domain-separation.md §10). Used for typed,
 * by-constructor-type injection into the listener classes only - the {@code
 * @RabbitListener} annotation attribute itself reads the same {@code
 * notify.messaging.queue.*} keys via property-placeholder syntax, not via this bean
 * (design doc §6 Decision 2).
 */
@ConfigurationProperties(prefix = "notify.messaging.queue")
public record NotifyQueueNamesProperties(String email, String prep) {
}
