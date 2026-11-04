package cl.campuslab.kafkaadmin.kafka;

/**
 * 409 (kafka-admin-write.md §5.1): el llamador es ADMIN y la solicitud es valida, pero el
 * recurso pertenece a la topologia declarada en {@code KafkaTopology} o es un consumer
 * group del sistema, y no puede mutarse por esta API. Se lanza antes de tocar Kafka.
 */
public class ProtectedTopologyResourceException extends RuntimeException {

    public ProtectedTopologyResourceException(String resourceType, String name, String operation) {
        this(resourceType, name, operation, "pertenece a la topologia base de Kafka (KafkaTopology)");
    }

    public ProtectedTopologyResourceException(String resourceType, String name, String operation, String reason) {
        super("Operacion '" + operation + "' rechazada sobre " + resourceType + " '" + name + "': "
                + reason + " y no puede modificarse por esta API.");
    }
}
