package cl.campuslab.kafkaadmin.kafka;

/** 409 (kafka-admin-write.md §6.3): topico ya existente o consumer group con miembros activos. */
public class AdminResourceConflictException extends RuntimeException {

    public AdminResourceConflictException(String message) {
        super(message);
    }
}
