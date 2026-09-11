package cl.campuslab.kafkaadmin.kafka;

public record PartitionLag(int partition, long currentOffset, long logEndOffset, long lag) {
}
