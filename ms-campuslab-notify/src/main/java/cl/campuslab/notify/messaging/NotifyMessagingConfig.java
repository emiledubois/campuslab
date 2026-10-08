package cl.campuslab.notify.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link NotifyQueueNamesProperties} (design doc mq-names-domain-separation.md
 * §6) - mirrors the shape {@code ms-campuslab-bookings}' own {@code
 * KafkaProducerConfig}/{@code RabbitPublisherConfig} already use for enabling a
 * {@code @ConfigurationProperties} record, notify's first use of that mechanism.
 */
@Configuration
@EnableConfigurationProperties(NotifyQueueNamesProperties.class)
public class NotifyMessagingConfig {
}
