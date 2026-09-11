package cl.campuslab.kafkaadmin.kafka;

import cl.campuslab.kafkaadmin.topology.KafkaAdminProperties;
import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import cl.campuslab.kafkaadmin.topology.KafkaTopology.TopicSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The sole owner of Kafka topology (design doc §1/§5.1/CLAUDE.md's "Broker admin
 * services" convention) - unlike RabbitMQ's {@code Declarables} (a genuine no-op
 * redeclare), {@code AdminClient.createTopics} ALWAYS throws {@link
 * TopicExistsException} against an already-existing topic (design doc §5.1,
 * investigated, not assumed). So creation and verification are two explicitly separate
 * steps: (1) create, catching {@code TopicExistsException} per-topic as "already
 * exists, not a failure"; (2) for every topic, newly created or pre-existing, describe
 * it and compare against the expected partition count/replication factor/cleanup
 * policy/retention, populating {@code configMatches} - a genuine mismatch is logged
 * ERROR but never crashes the service (§9 AC2).
 */
@Service
public class TopologyInspectionService {

    private static final Logger log = LoggerFactory.getLogger(TopologyInspectionService.class);
    private static final long DESCRIBE_TIMEOUT_SECONDS = 5;

    private final Admin adminClient;
    private final KafkaAdminProperties properties;
    private final KafkaReachabilityChecker reachabilityChecker;

    public TopologyInspectionService(
            Admin adminClient, KafkaAdminProperties properties, KafkaReachabilityChecker reachabilityChecker) {
        this.adminClient = adminClient;
        this.properties = properties;
        this.reachabilityChecker = reachabilityChecker;
    }

    /**
     * Called once at startup (design doc §5.1/§9 AC1) - creates any topic that doesn't
     * yet exist, then verifies every topic's actual config against the expected one,
     * logging the outcome per topic. Never throws: a broker that is unreachable at
     * startup, or a genuine creation/config failure, is logged ERROR and does not crash
     * the service (§9 AC1/AC2's "still starts" requirement).
     */
    public void createAndVerifyAtStartup() {
        try {
            createMissingTopics();
        } catch (Exception ex) {
            log.error("Kafka topology: outcome=[STARTUP_CREATE_FAILED] exceptionClass=[{}] exceptionMessage=[{}]",
                    ex.getClass().getName(), ex.getMessage());
        }
        for (TopicInfo info : listTopicsIgnoringReachability()) {
            if (info.configMatches()) {
                log.info("Kafka topology: outcome=[VERIFIED] topic=[{}] partitions=[{}] replicationFactor=[{}]",
                        info.name(), info.actualPartitions(), info.actualReplicationFactor());
            } else {
                log.error("Kafka topology: outcome=[CONFIG_MISMATCH] topic=[{}] expectedPartitions=[{}] "
                                + "actualPartitions=[{}] expectedReplicationFactor=[{}] actualReplicationFactor=[{}] "
                                + "expectedCleanupPolicy=[{}] actualCleanupPolicy=[{}] expectedRetentionMs=[{}] actualRetentionMs=[{}]",
                        info.name(), info.expectedPartitions(), info.actualPartitions(),
                        info.expectedReplicationFactor(), info.actualReplicationFactor(),
                        info.expectedCleanupPolicy(), info.actualCleanupPolicy(),
                        info.expectedRetentionMs(), info.actualRetentionMs());
            }
        }
    }

    public List<TopicInfo> listTopics() {
        reachabilityChecker.verifyReachable();
        return listTopicsIgnoringReachability();
    }

    private List<TopicInfo> listTopicsIgnoringReachability() {
        return KafkaTopology.ALL_TOPICS.stream().map(this::verify).toList();
    }

    private void createMissingTopics() {
        List<NewTopic> newTopics = KafkaTopology.ALL_TOPICS.stream()
                .map(spec -> new NewTopic(spec.name(), spec.partitions(), properties.topic().replicationFactor())
                        .configs(Map.of(
                                TopicConfig.CLEANUP_POLICY_CONFIG, spec.cleanupPolicy(),
                                TopicConfig.RETENTION_MS_CONFIG, String.valueOf(spec.retentionMs()))))
                .toList();

        CreateTopicsResult result = adminClient.createTopics(newTopics);
        result.values().forEach((topicName, future) -> {
            try {
                future.get(DESCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Kafka topology: outcome=[CREATED] topic=[{}]", topicName);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof TopicExistsException) {
                    log.info("Kafka topology: outcome=[ALREADY_EXISTS] topic=[{}]", topicName);
                } else {
                    log.error("Kafka topology: outcome=[CREATE_FAILED] topic=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                            topicName, ex.getCause().getClass().getName(), ex.getCause().getMessage());
                }
            } catch (InterruptedException | TimeoutException ex) {
                log.error("Kafka topology: outcome=[CREATE_FAILED] topic=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                        topicName, ex.getClass().getName(), ex.getMessage());
            }
        });
    }

    private TopicInfo verify(TopicSpec spec) {
        short expectedReplicationFactor = properties.topic().replicationFactor();
        try {
            TopicDescription description = adminClient.describeTopics(List.of(spec.name()))
                    .topicNameValues().get(spec.name())
                    .get(DESCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int actualPartitions = description.partitions().size();
            int actualReplicationFactor = description.partitions().isEmpty()
                    ? 0 : description.partitions().get(0).replicas().size();

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, spec.name());
            Config config = adminClient.describeConfigs(List.of(resource)).values().get(resource)
                    .get(DESCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String actualCleanupPolicy = configValue(config, TopicConfig.CLEANUP_POLICY_CONFIG);
            Long actualRetentionMs = parseLong(configValue(config, TopicConfig.RETENTION_MS_CONFIG));

            boolean matches = actualPartitions == spec.partitions()
                    && actualReplicationFactor == expectedReplicationFactor
                    && spec.cleanupPolicy().equals(actualCleanupPolicy)
                    && actualRetentionMs != null && spec.retentionMs() == actualRetentionMs;

            return new TopicInfo(
                    spec.name(), spec.partitions(), actualPartitions,
                    expectedReplicationFactor, actualReplicationFactor,
                    spec.cleanupPolicy(), actualCleanupPolicy,
                    spec.retentionMs(), actualRetentionMs, matches);
        } catch (Exception ex) {
            return new TopicInfo(
                    spec.name(), spec.partitions(), 0,
                    expectedReplicationFactor, 0,
                    spec.cleanupPolicy(), null,
                    spec.retentionMs(), null, false);
        }
    }

    private static String configValue(Config config, String key) {
        return config.get(key) != null ? config.get(key).value() : null;
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
