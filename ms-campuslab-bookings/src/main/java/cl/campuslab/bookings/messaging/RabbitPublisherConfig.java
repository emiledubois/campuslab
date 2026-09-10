package cl.campuslab.bookings.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bookings never declares topology (design doc §2.1/§5.1 - "bookings never declares
 * topology, assumes mq-admin already has") - this is publish-only wiring: a JSON message
 * converter (so notify's own Jackson deserialization sees the exact envelope shape) and
 * a {@code RabbitTemplate} pointed at the shared broker connection already configured
 * via {@code spring.rabbitmq.*} (RABBITMQ_HOST/RABBITMQ_PORT/RABBITMQ_USER/
 * RABBITMQ_PASSWORD, design doc §7 A05).
 */
@Configuration
public class RabbitPublisherConfig {

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}
