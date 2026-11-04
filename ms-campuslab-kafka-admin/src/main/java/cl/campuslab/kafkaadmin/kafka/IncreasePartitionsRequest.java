package cl.campuslab.kafkaadmin.kafka;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de {@code POST /api/admin/kafka/topics/{name}/partitions}: cantidad total final (§3.4). */
public record IncreasePartitionsRequest(
        @NotNull(message = "es obligatorio")
        @Min(value = 2, message = "debe ser al menos 2")
        @Max(value = 12, message = "debe ser como maximo 12")
        Integer totalCount) {
}
