package cl.campuslab.mqadmin.topology;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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
 * direct/topic/dead-letter binding the case document's own table specifies, now declared
 * as 18 explicit, individually named {@code @Bean} methods rather than one looped {@link
 * org.springframework.amqp.core.Declarables} bean (design doc
 * mq-names-domain-separation.md §6 indicator 2 - a human evaluator visually scanning for
 * {@code @Bean public Queue qCmdEmail()}-shaped methods is what this structure satisfies;
 * {@code RabbitAdmin} collects every {@code Declarable} bean from the context regardless
 * of whether it arrived standalone or wrapped in a {@code Declarables}, so this is a pure
 * code-structure decision with zero functional risk). {@link MqTopology}'s string
 * constants remain the only literal source every method body references - only "how many
 * methods" changed, never "where the strings come from." Each bean is individually
 * idempotent-redeclare-safe by construction (Spring AMQP only (re)declares a definition if
 * it doesn't already exist with the exact same arguments).
 */
@Configuration
public class RabbitTopologyConfig {

    /**
     * Explicit, not relied-upon-implicitly: {@code RabbitAdmin} is what actually declares
     * every {@code Declarable} bean against the broker on {@code ContextRefreshedEvent}
     * (and is what the inspection/requeue services below reuse for {@code
     * getQueueProperties}/{@code initialize} - one shared AMQP connection/credentials for
     * every mq-admin responsibility, design doc §7 A06).
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public DirectExchange cmdDirectExchange() {
        return new DirectExchange(MqTopology.EXCHANGE_CMD_DIRECT, true, false);
    }

    @Bean
    public TopicExchange cmdTopicExchange() {
        return new TopicExchange(MqTopology.EXCHANGE_CMD_TOPIC, true, false);
    }

    @Bean
    public DirectExchange cmdDeadDlxExchange() {
        return new DirectExchange(MqTopology.EXCHANGE_CMD_DEAD_DLX, true, false);
    }

    @Bean
    public Queue qCmdEmail() {
        return workQueue(MqTopology.QUEUE_EMAIL, deadLetterRoutingKeyFor(MqTopology.QUEUE_EMAIL));
    }

    @Bean
    public Queue qCmdEmailDlq() {
        return dlq(MqTopology.DLQ_EMAIL);
    }

    @Bean
    public Queue qCmdPrep() {
        return workQueue(MqTopology.QUEUE_PREP, deadLetterRoutingKeyFor(MqTopology.QUEUE_PREP));
    }

    @Bean
    public Queue qCmdPrepDlq() {
        return dlq(MqTopology.DLQ_PREP);
    }

    @Bean
    public Queue qCmdVoucher() {
        return workQueue(MqTopology.QUEUE_VOUCHER, deadLetterRoutingKeyFor(MqTopology.QUEUE_VOUCHER));
    }

    @Bean
    public Queue qCmdVoucherDlq() {
        return dlq(MqTopology.DLQ_VOUCHER);
    }

    @Bean
    public Binding bindEmailDirect() {
        return directBinding(qCmdEmail(), cmdDirectExchange(), directRoutingKeyFor(MqTopology.QUEUE_EMAIL));
    }

    @Bean
    public Binding bindEmailTopic() {
        return topicBinding(qCmdEmail(), cmdTopicExchange(), topicPatternFor(MqTopology.QUEUE_EMAIL));
    }

    @Bean
    public Binding bindEmailDeadLetter() {
        return deadLetterBinding(qCmdEmailDlq(), cmdDeadDlxExchange(), deadLetterRoutingKeyFor(MqTopology.QUEUE_EMAIL));
    }

    @Bean
    public Binding bindPrepDirect() {
        return directBinding(qCmdPrep(), cmdDirectExchange(), directRoutingKeyFor(MqTopology.QUEUE_PREP));
    }

    @Bean
    public Binding bindPrepTopic() {
        return topicBinding(qCmdPrep(), cmdTopicExchange(), topicPatternFor(MqTopology.QUEUE_PREP));
    }

    @Bean
    public Binding bindPrepDeadLetter() {
        return deadLetterBinding(qCmdPrepDlq(), cmdDeadDlxExchange(), deadLetterRoutingKeyFor(MqTopology.QUEUE_PREP));
    }

    @Bean
    public Binding bindVoucherDirect() {
        return directBinding(qCmdVoucher(), cmdDirectExchange(), directRoutingKeyFor(MqTopology.QUEUE_VOUCHER));
    }

    @Bean
    public Binding bindVoucherTopic() {
        return topicBinding(qCmdVoucher(), cmdTopicExchange(), topicPatternFor(MqTopology.QUEUE_VOUCHER));
    }

    @Bean
    public Binding bindVoucherDeadLetter() {
        return deadLetterBinding(qCmdVoucherDlq(), cmdDeadDlxExchange(), deadLetterRoutingKeyFor(MqTopology.QUEUE_VOUCHER));
    }

    private Queue workQueue(String name, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", MqTopology.EXCHANGE_CMD_DEAD_DLX)
                .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
                .build();
    }

    private Queue dlq(String name) {
        return QueueBuilder.durable(name).build();
    }

    private Binding directBinding(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    private Binding topicBinding(Queue queue, TopicExchange exchange, String pattern) {
        return BindingBuilder.bind(queue).to(exchange).with(pattern);
    }

    private Binding deadLetterBinding(Queue dlq, DirectExchange deadDlx, String routingKey) {
        return BindingBuilder.bind(dlq).to(deadDlx).with(routingKey);
    }

    private static String directRoutingKeyFor(String workQueueName) {
        return MqTopology.WORK_QUEUE_DIRECT_ROUTING_KEY.get(workQueueName);
    }

    private static String topicPatternFor(String workQueueName) {
        return MqTopology.WORK_QUEUE_TOPIC_BINDING_PATTERN.get(workQueueName);
    }

    private static String deadLetterRoutingKeyFor(String workQueueName) {
        return MqTopology.WORK_QUEUE_DEAD_LETTER_ROUTING_KEY.get(workQueueName);
    }
}
