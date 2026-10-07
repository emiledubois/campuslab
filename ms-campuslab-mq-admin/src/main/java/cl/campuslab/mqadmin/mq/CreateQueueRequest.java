package cl.campuslab.mqadmin.mq;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body for {@code POST /api/admin/mq/queues} (Slice A design doc §3.2/§3.10) -
 * {@code durable}/{@code exclusive}/{@code autoDelete} are nullable {@code Boolean}, not
 * primitive {@code boolean}, on purpose: an omitted field must resolve to this project's
 * "durable by default" convention in {@link RabbitAdminService}, not to Jackson's
 * implicit {@code false} for a missing primitive.
 */
public record CreateQueueRequest(
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = NAME_REGEX, message = "must be 1-255 chars of [A-Za-z0-9_.:-] and not start with 'amq.'")
        String name,
        Boolean durable,
        Boolean exclusive,
        Boolean autoDelete) {

    /** Identifier pattern shared by queue/exchange {@code name} and binding
     * {@code source}/{@code destination} (design doc §3.10) - single source of truth so
     * {@link CreateExchangeRequest} and {@link BindingRequest} reference it, not redeclare it. */
    public static final String NAME_REGEX = "^(?!amq\\.)[A-Za-z0-9_.:-]{1,255}$";
}
