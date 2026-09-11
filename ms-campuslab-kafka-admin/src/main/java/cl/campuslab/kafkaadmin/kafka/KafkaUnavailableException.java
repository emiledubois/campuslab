package cl.campuslab.kafkaadmin.kafka;

/**
 * Maps to 503 (design doc §3's "Kafka unreachable from kafka-admin") - the direct
 * analogue of mq-admin's {@code RabbitUnavailableException}.
 */
public class KafkaUnavailableException extends RuntimeException {

    public KafkaUnavailableException(Throwable cause) {
        super("Kafka is currently unreachable.", cause);
    }
}
