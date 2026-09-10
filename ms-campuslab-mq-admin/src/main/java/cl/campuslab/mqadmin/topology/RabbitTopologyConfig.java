package cl.campuslab.mqadmin.topology;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sole owner of RabbitMQ topology (design doc §1/§5.1/CLAUDE.md's "Broker admin
 * services" convention) - 3 exchanges, 6 queues (3 work queues + their DLQs), and every
 * direct/topic/dead-letter binding the case document's own table specifies. A single
 * {@link Declarables} bean is idempotent-redeclare-safe by construction (Spring AMQP
 * only (re)declares each definition if it doesn't already exist with the exact same
 * arguments) - see MqTopology's own "never change in place" warning for why every field
 * here must stay byte-for-byte identical across restarts once real data exists downstream.
 */
@Configuration
public class RabbitTopologyConfig {

    /**
     * Explicit, not relied-upon-implicitly: {@code RabbitAdmin} is what actually declares
     * every {@link Declarables} entry against the broker on {@code ContextRefreshedEvent}
     * (and is what the inspection/requeue services below reuse for {@code
     * getQueueProperties}/{@code initialize} - one shared AMQP connection/credentials for
     * every mq-admin responsibility, design doc §7 A06).
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public Declarables topology() {
        List<Declarable> declarables = new ArrayList<>();

        DirectExchange cmdDirect = new DirectExchange(MqTopology.EXCHANGE_CMD_DIRECT, true, false);
        TopicExchange cmdTopic = new TopicExchange(MqTopology.EXCHANGE_CMD_TOPIC, true, false);
        DirectExchange cmdDeadDlx = new DirectExchange(MqTopology.EXCHANGE_CMD_DEAD_DLX, true, false);
        declarables.add(cmdDirect);
        declarables.add(cmdTopic);
        declarables.add(cmdDeadDlx);

        for (String workQueueName : MqTopology.WORK_QUEUE_DIRECT_ROUTING_KEY.keySet()) {
            String dlqName = dlqNameFor(workQueueName);
            String deadLetterRoutingKey = MqTopology.WORK_QUEUE_DEAD_LETTER_ROUTING_KEY.get(workQueueName);

            Queue workQueue = QueueBuilder.durable(workQueueName)
                    .withArgument("x-dead-letter-exchange", MqTopology.EXCHANGE_CMD_DEAD_DLX)
                    .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
                    .build();
            Queue dlq = QueueBuilder.durable(dlqName).build();
            declarables.add(workQueue);
            declarables.add(dlq);

            Binding directBinding = BindingBuilder.bind(workQueue).to(cmdDirect)
                    .with(MqTopology.WORK_QUEUE_DIRECT_ROUTING_KEY.get(workQueueName));
            Binding topicBinding = BindingBuilder.bind(workQueue).to(cmdTopic)
                    .with(MqTopology.WORK_QUEUE_TOPIC_BINDING_PATTERN.get(workQueueName));
            Binding deadLetterBinding = BindingBuilder.bind(dlq).to(cmdDeadDlx).with(deadLetterRoutingKey);
            declarables.add(directBinding);
            declarables.add(topicBinding);
            declarables.add(deadLetterBinding);
        }

        return new Declarables(declarables);
    }

    private static String dlqNameFor(String workQueueName) {
        return MqTopology.DLQ_TO_WORK_QUEUE.entrySet().stream()
                .filter(entry -> entry.getValue().equals(workQueueName))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No DLQ mapped for work queue " + workQueueName));
    }
}
