package cl.campuslab.kafkaadmin.kafka;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Cuerpo de {@code POST /api/admin/kafka/consumer-groups/{groupId}/reset-offsets}
 * (kafka-admin-write.md §3.6). {@code strategy} es String + {@code @Pattern} para obtener
 * un 400 claro en vez de un error de deserializacion de enum.
 */
public record ResetOffsetsRequest(
        @NotBlank(message = "no debe estar vacio")
        @Pattern(regexp = CreateTopicRequest.TOPIC_NAME_REGEX, message = CreateTopicRequest.TOPIC_NAME_MESSAGE)
        String topic,
        @NotBlank(message = "no debe estar vacio")
        @Pattern(regexp = "^(EARLIEST|LATEST)$", message = "debe ser EARLIEST o LATEST")
        String strategy) {

    public static final String EARLIEST = "EARLIEST";
    public static final String LATEST = "LATEST";
}
