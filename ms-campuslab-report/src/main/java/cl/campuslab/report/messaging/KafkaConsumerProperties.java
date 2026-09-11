package cl.campuslab.report.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka")
public record KafkaConsumerProperties(String bootstrapServers) {
}
