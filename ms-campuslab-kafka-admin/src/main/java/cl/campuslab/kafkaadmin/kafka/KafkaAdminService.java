package cl.campuslab.kafkaadmin.kafka;

import cl.campuslab.kafkaadmin.topology.KafkaAdminProperties;
import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Service Facade sobre {@code Admin} para las operaciones con escritura
 * (kafka-admin-write.md §6), analogo a {@code RabbitAdminService} de mq-admin. Orden fijo en
 * cada metodo (§6.1): (1) guardas de {@link KafkaTopology} -> 409 sin tocar Kafka;
 * (2) {@link TopicConfigPolicy} -> 400 sin tocar Kafka; (3) alcanzabilidad -> 503;
 * (4) pre-chequeo de existencia/estado; (5) llamada mutante con traduccion de excepciones.
 *
 * <p>La existencia se verifica explicitamente antes de borrar (§6.2): {@code deleteTopics}
 * y {@code deleteConsumerGroups} no son una fuente confiable de "no existia".
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);

    private static final long TIMEOUT_SECONDS = 5;
    private static final long DELETE_POLL_INTERVAL_MS = 200;
    private static final long DELETE_WAIT_MAX_MS = 10_000;

    private static final String TOPIC = "topico";
    private static final String GROUP = "consumer group";

    private final Admin adminClient;
    private final KafkaAdminProperties properties;
    private final KafkaReachabilityChecker reachabilityChecker;

    // @Lazy obligatorio en el punto de inyeccion: ver Javadoc de KafkaAdminClientConfig.
    public KafkaAdminService(
            @Lazy Admin adminClient, KafkaAdminProperties properties, KafkaReachabilityChecker reachabilityChecker) {
        this.adminClient = adminClient;
        this.properties = properties;
        this.reachabilityChecker = reachabilityChecker;
    }

    public AdminTopicInfo createTopic(CreateTopicRequest request) {
        String name = request.name();
        if (KafkaTopology.isManagedTopic(name)) {
            throw new ProtectedTopologyResourceException(TOPIC, name, "create");
        }
        Optional<String> colliding = KafkaTopology.collidingManagedTopic(name);
        if (colliding.isPresent()) {
            throw new ProtectedTopologyResourceException(TOPIC, name, "create",
                    "colisiona con el topico gestionado '" + colliding.get() + "' ('.' y '_' son equivalentes en Kafka)");
        }
        TopicConfigPolicy.validate(request.configs());

        reachabilityChecker.verifyReachable();

        int replicationFactor = request.replicationFactor() != null
                ? request.replicationFactor()
                : properties.topic().replicationFactor();
        int brokers = await(adminClient.describeCluster().nodes(), TOPIC, name).size();
        if (replicationFactor > brokers) {
            throw new InvalidKafkaAdminRequestException("replicationFactor " + replicationFactor
                    + " excede la cantidad de brokers disponibles (" + brokers + ").");
        }

        Map<String, String> configs = request.configs() != null ? Map.copyOf(request.configs()) : Map.of();
        NewTopic newTopic = new NewTopic(name, request.partitions(), (short) replicationFactor).configs(configs);
        await(adminClient.createTopics(List.of(newTopic)).all(), TOPIC, name);

        return new AdminTopicInfo(name, request.partitions(), replicationFactor, configs);
    }

    public void deleteTopic(String name) {
        if (KafkaTopology.isManagedTopic(name)) {
            throw new ProtectedTopologyResourceException(TOPIC, name, "delete");
        }

        reachabilityChecker.verifyReachable();

        assertTopicExists(name);
        await(adminClient.deleteTopics(List.of(name)).all(), TOPIC, name);
        awaitTopicDisappears(name);
    }

    public TopicConfigInfo updateTopicConfig(String name, UpdateTopicConfigRequest request) {
        if (KafkaTopology.isManagedTopic(name)) {
            throw new ProtectedTopologyResourceException(TOPIC, name, "update-config");
        }
        TopicConfigPolicy.validate(request.configs());

        reachabilityChecker.verifyReachable();

        assertTopicExists(name);
        List<AlterConfigOp> operations = new ArrayList<>();
        request.configs().forEach((key, value) ->
                operations.add(new AlterConfigOp(new ConfigEntry(key, value), AlterConfigOp.OpType.SET)));
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        await(adminClient.incrementalAlterConfigs(Map.of(resource, operations)).all(), TOPIC, name);

        return new TopicConfigInfo(name, new LinkedHashMap<>(request.configs()));
    }

    public PartitionChangeInfo increasePartitions(String name, IncreasePartitionsRequest request) {
        if (KafkaTopology.isManagedTopic(name)) {
            throw new ProtectedTopologyResourceException(TOPIC, name, "increase-partitions");
        }

        reachabilityChecker.verifyReachable();

        int current = describeTopic(name).partitions().size();
        int requested = request.totalCount();
        if (requested <= current) {
            throw new InvalidKafkaAdminRequestException("La nueva cantidad de particiones (" + requested
                    + ") debe ser mayor que la actual (" + current + "); Kafka no permite reducir particiones.");
        }
        await(adminClient.createPartitions(Map.of(name, NewPartitions.increaseTo(requested))).all(), TOPIC, name);

        return new PartitionChangeInfo(name, current, requested);
    }

    public void deleteConsumerGroup(String groupId) {
        if (KafkaTopology.isManagedConsumerGroup(groupId)) {
            throw new ProtectedTopologyResourceException(GROUP, groupId, "delete");
        }

        reachabilityChecker.verifyReachable();

        assertGroupExists(groupId);
        try {
            adminClient.deleteConsumerGroups(List.of(groupId)).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw KafkaExceptionTranslator.translate(ex, GROUP, groupId, "eliminarlo");
        }
    }

    public OffsetResetResult resetOffsets(String groupId, ResetOffsetsRequest request) {
        String strategy = request.strategy();
        if (KafkaTopology.isManagedConsumerGroup(groupId) && ResetOffsetsRequest.LATEST.equals(strategy)) {
            throw new ProtectedTopologyResourceException(GROUP, groupId, "reset-offsets LATEST",
                    "es un grupo del sistema; solo se permite EARLIEST, sus consumidores son idempotentes por eventId");
        }

        reachabilityChecker.verifyReachable();

        assertGroupExists(groupId);
        String topic = request.topic();
        List<TopicPartition> partitions = describeTopic(topic).partitions().stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        assertGroupHasNoMembers(groupId);

        OffsetSpec spec = ResetOffsetsRequest.EARLIEST.equals(strategy) ? OffsetSpec.earliest() : OffsetSpec.latest();
        Map<TopicPartition, OffsetSpec> offsetRequest = new LinkedHashMap<>();
        partitions.forEach(partition -> offsetRequest.put(partition, spec));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> targets =
                await(adminClient.listOffsets(offsetRequest).all(), TOPIC, topic);

        Map<TopicPartition, OffsetAndMetadata> newOffsets = new LinkedHashMap<>();
        targets.forEach((partition, info) -> newOffsets.put(partition, new OffsetAndMetadata(info.offset())));
        try {
            adminClient.alterConsumerGroupOffsets(groupId, newOffsets).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw KafkaExceptionTranslator.translate(ex, GROUP, groupId, "reiniciar sus offsets");
        }

        List<PartitionOffset> result = newOffsets.entrySet().stream()
                .map(entry -> new PartitionOffset(entry.getKey().partition(), entry.getValue().offset()))
                .sorted((a, b) -> Integer.compare(a.partition(), b.partition()))
                .toList();
        return new OffsetResetResult(groupId, topic, strategy, result);
    }

    private void assertTopicExists(String name) {
        if (!listTopicNames(name).contains(name)) {
            throw new AdminResourceNotFoundException(TOPIC, name);
        }
    }

    private Set<String> listTopicNames(String name) {
        return await(adminClient.listTopics().names(), TOPIC, name);
    }

    private TopicDescription describeTopic(String name) {
        return await(adminClient.describeTopics(List.of(name)).topicNameValues().get(name), TOPIC, name);
    }

    private void assertGroupExists(String groupId) {
        Collection<ConsumerGroupListing> groups = await(adminClient.listConsumerGroups().all(), GROUP, groupId);
        boolean exists = groups.stream().map(ConsumerGroupListing::groupId).anyMatch(groupId::equals);
        if (!exists) {
            throw new AdminResourceNotFoundException(GROUP, groupId);
        }
    }

    private void assertGroupHasNoMembers(String groupId) {
        Map<String, ConsumerGroupDescription> descriptions =
                await(adminClient.describeConsumerGroups(List.of(groupId)).all(), GROUP, groupId);
        ConsumerGroupDescription description = descriptions.get(groupId);
        if (description != null && !description.members().isEmpty()) {
            throw KafkaExceptionTranslator.activeMembersConflict(groupId, "reiniciar sus offsets");
        }
    }

    /**
     * El borrado de topicos es asincrono en el broker: se espera (acotado) a que deje de
     * listarse para que un segundo DELETE vea un 404 real (§6.1). Si vence el plazo se
     * responde igual 204, solo con WARN.
     */
    private void awaitTopicDisappears(String name) {
        long deadline = System.currentTimeMillis() + DELETE_WAIT_MAX_MS;
        while (true) {
            try {
                if (!adminClient.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(name)) {
                    return;
                }
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Kafka admin op: op=[delete] type=[topic] name=[{}] outcome=[DELETE_PENDING] exceptionClass=[{}]",
                        name, ex.getClass().getName());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("Kafka admin op: op=[delete] type=[topic] name=[{}] outcome=[DELETE_PENDING]", name);
                return;
            }
            try {
                Thread.sleep(DELETE_POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Kafka admin op: op=[delete] type=[topic] name=[{}] outcome=[DELETE_PENDING]", name);
                return;
            }
        }
    }

    private static <T> T await(KafkaFuture<T> future, String resourceType, String name) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw KafkaExceptionTranslator.translate(ex, resourceType, name);
        }
    }
}
