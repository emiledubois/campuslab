package cl.campuslab.kafkaadmin.kafka;

import java.util.List;

public record TopicLag(String topic, long totalLag, List<PartitionLag> partitions) {
}
