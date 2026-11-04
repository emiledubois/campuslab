package cl.campuslab.kafkaadmin.kafka;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** Cuerpo de {@code PATCH /api/admin/kafka/topics/{name}/config} (kafka-admin-write.md §3.3). */
public record UpdateTopicConfigRequest(
        @NotEmpty(message = "debe incluir al menos una clave") Map<String, String> configs) {
}
