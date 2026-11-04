package cl.campuslab.kafkaadmin.kafka;

public record PartitionOffset(int partition, long offset) {
}
