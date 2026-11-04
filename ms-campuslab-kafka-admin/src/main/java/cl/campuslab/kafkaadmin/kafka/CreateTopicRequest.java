package cl.campuslab.kafkaadmin.kafka;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * Cuerpo de {@code POST /api/admin/kafka/topics} (kafka-admin-write.md §3.1/§3.7).
 * {@code replicationFactor} es nullable a proposito: si se omite se usa
 * {@code kafka.topic.replication-factor}. {@code configs} se valida con
 * {@link TopicConfigPolicy} en el servicio, no con anotaciones.
 */
public record CreateTopicRequest(
        @NotBlank(message = "no debe estar vacio")
        @Pattern(regexp = CreateTopicRequest.TOPIC_NAME_REGEX, message = CreateTopicRequest.TOPIC_NAME_MESSAGE)
        String name,
        @NotNull(message = "es obligatorio")
        @Min(value = 1, message = "debe ser al menos 1")
        @Max(value = 12, message = "debe ser como maximo 12")
        Integer partitions,
        @Min(value = 1, message = "debe ser al menos 1")
        @Max(value = 3, message = "debe ser como maximo 3")
        Integer replicationFactor,
        Map<String, String> configs) {

    public static final String TOPIC_NAME_REGEX = "^(?!\\.{1,2}$)[a-zA-Z0-9._-]{1,249}$";
    public static final String TOPIC_NAME_MESSAGE =
            "debe tener entre 1 y 249 caracteres de [a-zA-Z0-9._-] y no puede ser '.' ni '..'";
}
