package cl.campuslab.kafkaadmin.kafka;

/** 404 (kafka-admin-write.md §6.3): el topico o consumer group no existe. */
public class AdminResourceNotFoundException extends RuntimeException {

    public AdminResourceNotFoundException(String resourceType, String name) {
        super("No existe un " + resourceType + " llamado '" + name + "'.");
    }
}
