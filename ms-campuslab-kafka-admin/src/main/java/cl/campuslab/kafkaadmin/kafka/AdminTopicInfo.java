package cl.campuslab.kafkaadmin.kafka;

import java.util.Map;

/** Respuesta de creacion de topico: eco de lo solicitado (kafka-admin-write.md §3.1). */
public record AdminTopicInfo(String name, int partitions, int replicationFactor, Map<String, String> configs) {
}
