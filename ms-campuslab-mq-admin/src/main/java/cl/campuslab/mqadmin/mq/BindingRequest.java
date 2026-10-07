package cl.campuslab.mqadmin.mq;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Shared body for {@code POST /api/admin/mq/bindings} (create) and
 * {@code DELETE /api/admin/mq/bindings} (delete) - Slice A design doc §3.7/§3.8/§3.10. A
 * binding has no single scalar identifier suitable for a path segment (it is a 4-tuple),
 * so delete is a {@code DELETE} with a body - service-to-service only, no browser ever
 * sends this request this slice.
 */
public record BindingRequest(
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = CreateQueueRequest.NAME_REGEX, message = "must be 1-255 chars of [A-Za-z0-9_.:-] and not start with 'amq.'")
        String source,
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = CreateQueueRequest.NAME_REGEX, message = "must be 1-255 chars of [A-Za-z0-9_.:-] and not start with 'amq.'")
        String destination,
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "^(QUEUE|EXCHANGE)$", message = "must be QUEUE or EXCHANGE")
        String destinationType,
        @Size(max = 255, message = "must be at most 255 characters")
        String routingKey) {
}
