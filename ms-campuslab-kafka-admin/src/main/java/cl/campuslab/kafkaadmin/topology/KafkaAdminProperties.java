package cl.campuslab.kafkaadmin.topology;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code replicationFactor} is the one topology value allowed to differ between the
 * local and deploy profiles (CLAUDE.md's "Local vs deploy topology" rule; design doc
 * §5.1) - {@code 1} for local's single broker, {@code 3} for deploy's three-broker
 * cluster. Getting this wrong for local fails topic creation outright with
 * {@code InvalidReplicationFactorException} (design doc §5.1's explicit warning).
 */
@ConfigurationProperties(prefix = "kafka")
public record KafkaAdminProperties(String bootstrapServers, Topic topic) {

    public record Topic(short replicationFactor) {
    }
}
