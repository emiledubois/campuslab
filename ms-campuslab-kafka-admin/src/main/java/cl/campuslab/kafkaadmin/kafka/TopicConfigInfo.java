package cl.campuslab.kafkaadmin.kafka;

import java.util.Map;

/** Respuesta de modificacion de configuracion: eco de lo aplicado (kafka-admin-write.md §3.3). */
public record TopicConfigInfo(String name, Map<String, String> configs) {
}
