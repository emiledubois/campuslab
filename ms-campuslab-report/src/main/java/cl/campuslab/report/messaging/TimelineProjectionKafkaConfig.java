package cl.campuslab.report.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Container factory del listener de {@code audit.timeline} (audit-timeline-compacted.md
 * §5.4.4). Solo la factory es bean: consumer factory, recoverer y error handler se
 * construyen aqui dentro para no agregar segundos beans de esos tipos que vuelvan ambigua
 * la autoconfiguracion o los puntos de inyeccion de {@link KafkaConsumerConfig}.
 */
@Configuration
public class TimelineProjectionKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(TimelineProjectionKafkaConfig.class);

    public static final String GROUP_ID = "report-timeline-projection";
    public static final String DLT_TOPIC_TIMELINE_PROJECTION = "audit.timeline.report-timeline-projection.DLT";

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> timelineProjectionKafkaListenerContainerFactory(
            KafkaConsumerProperties properties,
            @Qualifier("dltRawKafkaTemplate") KafkaTemplate<String, byte[]> dltRawKafkaTemplate,
            @Qualifier("dltJsonKafkaTemplate") KafkaTemplate<String, Object> dltJsonKafkaTemplate) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BookingStateSnapshotEnvelope.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.campuslab.report.messaging");

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltRawKafkaTemplate);
        templates.put(BookingStateSnapshotEnvelope.class, dltJsonKafkaTemplate);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                templates, (record, ex) -> new TopicPartition(DLT_TOPIC_TIMELINE_PROJECTION, -1));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new SafeDeadLetterRecoverer(recoverer), new FixedBackOff(1000L, 2));
        errorHandler.addNotRetryableExceptions(UnrecognizedEventTypeException.class, UnparseableEnvelopeException.class);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> log.warn(
                "Retry attempt=[{}] failed for record topic=[{}] partition=[{}] offset=[{}] exceptionClass=[{}]",
                deliveryAttempt, record.topic(), record.partition(), record.offset(), exception.getClass().getName()));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Igual que reportKafkaListenerContainerFactory: lo arranca KafkaListenerStartupRetryTask.
        factory.setAutoStartup(false);
        return factory;
    }
}
