package cl.campuslab.kafkaadmin.kafka;

/** 400 (kafka-admin-write.md §6.3): argumento invalido detectado por el servicio o por el broker. */
public class InvalidKafkaAdminRequestException extends RuntimeException {

    public InvalidKafkaAdminRequestException(String message) {
        super(message);
    }
}
