package cl.campuslab.kafkaadmin.kafka;

import cl.campuslab.kafkaadmin.topology.KafkaTopology;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Read-only DLT depth inspection (design doc §3 - no requeue-from-DLT operation this
 * slice, unlike mq-admin's; the case document's own table gives kafka-admin's row no
 * requeue verb). {@code approximateMessageCount} is {@code latestOffset - earliestOffset}
 * summed across partitions via {@code AdminClient.listOffsets} - never a message-body
 * read, the smallest-trust option (design doc §7 A08).
 */
@Service
public class DltInspectionService {

    private static final long TIMEOUT_SECONDS = 5;

    private final Admin adminClient;
    private final KafkaReachabilityChecker reachabilityChecker;

    public DltInspectionService(@Lazy Admin adminClient, KafkaReachabilityChecker reachabilityChecker) {
        this.adminClient = adminClient;
        this.reachabilityChecker = reachabilityChecker;
    }

    public List<DltInfo> listDlts() {
        reachabilityChecker.verifyReachable();
        return KafkaTopology.DLT_NAMES.stream().map(this::toDltInfo).toList();
    }

    private DltInfo toDltInfo(String dltName) {
        List<TopicPartition> partitions = partitionsOf(dltName);
        if (partitions.isEmpty()) {
            return new DltInfo(dltName, 0L);
        }

        Map<TopicPartition, Long> earliest = offsetsOf(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsetsOf(partitions, OffsetSpec.latest());

        long approximateMessageCount = partitions.stream()
                .mapToLong(partition -> Math.max(0,
                        latest.getOrDefault(partition, 0L) - earliest.getOrDefault(partition, 0L)))
                .sum();

        return new DltInfo(dltName, approximateMessageCount);
    }

    private List<TopicPartition> partitionsOf(String topicName) {
        try {
            DescribeTopicsResult result = adminClient.describeTopics(List.of(topicName));
            TopicDescription description = result.topicNameValues().get(topicName).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return description.partitions().stream()
                    .map(partitionInfo -> new TopicPartition(topicName, partitionInfo.partition()))
                    .toList();
        } catch (Exception ex) {
            // The DLT genuinely not existing yet (kafka-admin never ran) is not a broker-
            // unreachable case - report zero, don't 503 (design doc §5.4's own accepted gap).
            return List.of();
        }
    }

    private Map<TopicPartition, Long> offsetsOf(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(partition -> request.put(partition, spec));
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
