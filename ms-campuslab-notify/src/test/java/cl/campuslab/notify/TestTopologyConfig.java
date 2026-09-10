package cl.campuslab.notify;

import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only stand-in for ms-campuslab-mq-admin's real topology (design doc §5.3 -
 * "notify never declares topology, assumes mq-admin already has"). Declares only the
 * subset this slice's own tests touch (q.cmd.email/q.cmd.prep + their DLQs) - copied,
 * not shared, from mq-admin's own {@code RabbitTopologyConfig} per the no-cross-service-
 * code convention; this is test fixture code, not production wiring.
 */
@TestConfiguration
public class TestTopologyConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public Declarables topology() {
        DirectExchange cmdDirect = new DirectExchange("cmd.direct", true, false);
        DirectExchange cmdDeadDlx = new DirectExchange("cmd.dead.dlx", true, false);

        Queue emailQueue = QueueBuilder.durable("q.cmd.email")
                .withArgument("x-dead-letter-exchange", "cmd.dead.dlx")
                .withArgument("x-dead-letter-routing-key", "email.dlq")
                .build();
        Queue emailDlq = QueueBuilder.durable("q.cmd.email.dlq").build();
        Queue prepQueue = QueueBuilder.durable("q.cmd.prep")
                .withArgument("x-dead-letter-exchange", "cmd.dead.dlx")
                .withArgument("x-dead-letter-routing-key", "prep.dlq")
                .build();
        Queue prepDlq = QueueBuilder.durable("q.cmd.prep.dlq").build();

        Binding emailBinding = BindingBuilder.bind(emailQueue).to(cmdDirect).with("email.send");
        Binding prepBinding = BindingBuilder.bind(prepQueue).to(cmdDirect).with("prep.ticket");
        Binding emailDlqBinding = BindingBuilder.bind(emailDlq).to(cmdDeadDlx).with("email.dlq");
        Binding prepDlqBinding = BindingBuilder.bind(prepDlq).to(cmdDeadDlx).with("prep.dlq");

        List<Declarable> declarables = List.of(
                cmdDirect, cmdDeadDlx, emailQueue, emailDlq, prepQueue, prepDlq,
                emailBinding, prepBinding, emailDlqBinding, prepDlqBinding);
        return new Declarables(declarables);
    }
}
