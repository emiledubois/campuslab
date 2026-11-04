package cl.campuslab.kafkaadmin.kafka;

import java.util.List;

/** Respuesta del reinicio de offsets, una entrada por particion del topico (kafka-admin-write.md §3.6). */
public record OffsetResetResult(String groupId, String topic, String strategy, List<PartitionOffset> partitions) {
}
