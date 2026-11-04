package cl.campuslab.kafkaadmin.kafka;

/** Respuesta de aumento de particiones (kafka-admin-write.md §3.4). */
public record PartitionChangeInfo(String name, int previousPartitions, int partitions) {
}
