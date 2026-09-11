package cl.campuslab.bookings.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code requestTimeoutMs}/{@code maxBlockMs} default to 2000ms each (design doc §2.2/
 * §5.3 - "well under the 3-second .get() wait"), overridable so tests can point at a
 * reserved-for-documentation unreachable address (RFC 5737) and fail fast in
 * milliseconds rather than seconds, the same convention already used for
 * spring.rabbitmq.connection-timeout in this service's own test config.
 */
@ConfigurationProperties(prefix = "kafka")
public record KafkaProducerProperties(
        String bootstrapServers,
        @DefaultValue("2000") long requestTimeoutMs,
        @DefaultValue("2000") long maxBlockMs) {
}
