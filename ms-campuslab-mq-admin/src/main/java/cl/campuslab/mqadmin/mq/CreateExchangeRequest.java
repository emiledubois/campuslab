package cl.campuslab.mqadmin.mq;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body for {@code POST /api/admin/mq/exchanges} (Slice A design doc §3.5/§3.10). */
public record CreateExchangeRequest(
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = CreateQueueRequest.NAME_REGEX, message = "must be 1-255 chars of [A-Za-z0-9_.:-] and not start with 'amq.'")
        String name,
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "^(direct|topic|fanout|headers)$", message = "must be one of: direct, topic, fanout, headers")
        String type,
        Boolean durable,
        Boolean autoDelete) {
}
