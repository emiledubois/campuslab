package cl.campuslab.mqadmin.mq;

/**
 * Maps to 409 (Slice A design doc §5.1) - the request is syntactically valid and the
 * caller is correctly authorized (ADMIN), but the target name is one of the fixed
 * queues/exchanges/bindings {@code RabbitTopologyConfig} declares at startup, and must
 * never be mutated through this imperative management API.
 */
public class ProtectedTopologyResourceException extends RuntimeException {

    public ProtectedTopologyResourceException(String resourceType, String name, String operation) {
        super("Cannot " + operation + " " + resourceType + " '" + name + "': it is managed by the base "
                + "RabbitMQ topology (RabbitTopologyConfig) and must not be modified through this API.");
    }
}
