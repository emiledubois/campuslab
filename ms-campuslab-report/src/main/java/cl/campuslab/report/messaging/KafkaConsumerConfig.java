package cl.campuslab.report.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer group {@code report-service} (design doc §5.3). Two genuinely different
 * failure classes, handled differently (§2.4, decided explicitly, applied correctly
 * from day one rather than repeating {@code kafka-audit.md}'s QA iteration-1 bug):
 * deterministic/poison (unparseable envelope via {@code ErrorHandlingDeserializer}, or
 * an unrecognized {@code type}) never retries; a transient DB failure retries via
 * {@link FixedBackOff}(1000, 2) before dead-lettering. {@code AckMode.RECORD} is the
 * explicit-ACK/NACK-equivalent CLAUDE.md's envelope section requires.
 */
@Configuration
@EnableConfigurationProperties(KafkaConsumerProperties.class)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    public static final String DLT_TOPIC = "bookings.events.report-service.DLT";

    @Bean
    public ConsumerFactory<String, Object> reportConsumerFactory(KafkaConsumerProperties properties) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "report-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Bounded well below Kafka's 60s client defaults so a genuinely unreachable
        // broker fails fast rather than leaving a background thread retrying for a
        // full minute per Spring context - the same "fail fast" posture as audit's own.
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BookingStreamEnvelope.class.getName());
        // Never trust the producer's own __TypeId__ header (design doc §5.2/§7 A08/no-
        // cross-service-code convention) - report always binds onto its own copy.
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.campuslab.report.messaging");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ProducerFactory<String, byte[]> dltRawProducerFactory(KafkaConsumerProperties properties) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, byte[]> dltRawKafkaTemplate(ProducerFactory<String, byte[]> dltRawProducerFactory) {
        return new KafkaTemplate<>(dltRawProducerFactory);
    }

    @Bean
    public ProducerFactory<String, Object> dltJsonProducerFactory(KafkaConsumerProperties properties) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> dltJsonKafkaTemplate(ProducerFactory<String, Object> dltJsonProducerFactory) {
        return new KafkaTemplate<>(dltJsonProducerFactory);
    }

    /**
     * {@code DeadLetterPublishingRecoverer} attaches proper error-metadata headers
     * automatically ({@code kafka_dlt-exception-fqcn}/{@code -message}/{@code
     * -original-topic}/{@code -partition}/{@code -offset}). Two templates, keyed by
     * payload class (design doc §5.3): raw bytes for a genuine deserialization
     * failure (the value never made it past the deserializer), JSON for a
     * successfully-deserialized envelope whose DB retries were exhausted.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, byte[]> dltRawKafkaTemplate, KafkaTemplate<String, Object> dltJsonKafkaTemplate) {
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltRawKafkaTemplate);
        templates.put(BookingStreamEnvelope.class, dltJsonKafkaTemplate);
        return new DeadLetterPublishingRecoverer(templates, (record, ex) -> new org.apache.kafka.common.TopicPartition(DLT_TOPIC, -1));
    }

    @Bean
    public DefaultErrorHandler reportErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new SafeDeadLetterRecoverer(deadLetterPublishingRecoverer), new FixedBackOff(1000L, 2));
        errorHandler.addNotRetryableExceptions(UnrecognizedEventTypeException.class, UnparseableEnvelopeException.class);
        // §7 A09 requires the exhausted-retry count to be visible; this is the only hook
        // Spring Kafka exposes per failed delivery attempt (design doc §9 AC5).
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> log.warn(
                "Retry attempt=[{}] failed for record topic=[{}] partition=[{}] offset=[{}] exceptionClass=[{}]",
                deliveryAttempt, record.topic(), record.partition(), record.offset(), exception.getClass().getName()));
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> reportKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> reportConsumerFactory, DefaultErrorHandler reportErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(reportConsumerFactory);
        factory.setCommonErrorHandler(reportErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
