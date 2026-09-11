package cl.campuslab.kafkaadmin.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Service;

/**
 * Lag inspection via {@code AdminClient} only (design doc §7 A06 - no second port, no
 * Confluent REST Proxy) - {@code listConsumerGroups} discovers every group the broker
 * knows about (one this slice, {@code audit-service}), then {@code
 * listConsumerGroupOffsets}/{@code listOffsets} compute lag per topic-partition, the
 * same "committed offset vs. log end offset" arithmetic Kafka UI itself uses.
 */
@Service
public class ConsumerGroupInspectionService {

    private static final long TIMEOUT_SECONDS = 5;

    private final Admin adminClient;
    private final KafkaReachabilityChecker reachabilityChecker;

    public ConsumerGroupInspectionService(Admin adminClient, KafkaReachabilityChecker reachabilityChecker) {
        this.adminClient = adminClient;
        this.reachabilityChecker = reachabilityChecker;
    }

    public List<ConsumerGroupInfo> listGroups() {
        reachabilityChecker.verifyReachable();

        List<String> groupIds = listGroupIds();
        if (groupIds.isEmpty()) {
            return List.of();
        }

        Map<String, ConsumerGroupDescription> descriptions = describeGroups(groupIds);
        List<ConsumerGroupInfo> result = new ArrayList<>();
        for (String groupId : groupIds) {
            result.add(toConsumerGroupInfo(groupId, descriptions.get(groupId)));
        }
        return result;
    }

    private List<String> listGroupIds() {
        try {
            return adminClient.listConsumerGroups().all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId)
                    .toList();
        } catch (Exception ex) {
            throw new KafkaUnavailableException(ex);
        }
    }

    private Map<String, ConsumerGroupDescription> describeGroups(List<String> groupIds) {
        try {
            return adminClient.describeConsumerGroups(groupIds).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new KafkaUnavailableException(ex);
        }
    }

    private ConsumerGroupInfo toConsumerGroupInfo(String groupId, ConsumerGroupDescription description) {
        String state = description != null ? description.state().toString() : "UNKNOWN";

        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committedOffsets = committedOffsetsOf(groupId);
        if (committedOffsets.isEmpty()) {
            return new ConsumerGroupInfo(groupId, state, List.of());
        }

        Map<TopicPartition, Long> logEndOffsets = logEndOffsetsOf(committedOffsets.keySet());

        Map<String, List<PartitionLag>> byTopic = new LinkedHashMap<>();
        committedOffsets.forEach((topicPartition, offsetAndMetadata) -> {
            long currentOffset = offsetAndMetadata.offset();
            long logEndOffset = logEndOffsets.getOrDefault(topicPartition, currentOffset);
            long lag = Math.max(0, logEndOffset - currentOffset);
            byTopic.computeIfAbsent(topicPartition.topic(), key -> new ArrayList<>())
                    .add(new PartitionLag(topicPartition.partition(), currentOffset, logEndOffset, lag));
        });

        List<TopicLag> topics = byTopic.entrySet().stream()
                .map(entry -> {
                    List<PartitionLag> partitions = entry.getValue().stream()
                            .sorted((a, b) -> Integer.compare(a.partition(), b.partition()))
                            .toList();
                    long totalLag = partitions.stream().mapToLong(PartitionLag::lag).sum();
                    return new TopicLag(entry.getKey(), totalLag, partitions);
                })
                .toList();

        return new ConsumerGroupInfo(groupId, state, topics);
    }

    private Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committedOffsetsOf(String groupId) {
        try {
            return adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new KafkaUnavailableException(ex);
        }
    }

    private Map<TopicPartition, Long> logEndOffsetsOf(Set<TopicPartition> partitions) {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(partition -> request.put(partition, OffsetSpec.latest()));
        try {
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> results =
                    adminClient.listOffsets(request).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
            results.forEach((partition, info) -> offsets.put(partition, info.offset()));
            return offsets;
        } catch (Exception ex) {
            throw new KafkaUnavailableException(ex);
        }
    }
}
