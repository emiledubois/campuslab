package cl.campuslab.mqadmin.topology;

import static org.assertj.core.api.Assertions.assertThatCode;

import cl.campuslab.mqadmin.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * AC1's "idempotent redeclare, actually re-run, not just asserted" - declaring the exact
 * same {@link Declarables} twice against a real broker must be a genuine no-op, never a
 * {@code 406 PRECONDITION_FAILED}. Deliberately bypasses the full Spring context (which
 * would otherwise eagerly perform a real OIDC discovery HTTP call for
 * {@code JwtDeCoderConfig} - out of scope for this class, and unavailable/undesirable in
 * a sandboxed test run) - only the piece under test, {@code RabbitTopologyConfig}'s own
 * {@link Declarables} bean method, plus a hand-built {@code RabbitAdmin}, are exercised. A
 * literal process restart against infra/mq/compose.yml's local profile is verified live
 * per this task's own instructions.
 */
class RabbitTopologyConfigTest extends AbstractIntegrationTest {

    @Test
    void topology_declaredTwiceAgainstSameBroker_isIdempotent() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Declarables declarables = new RabbitTopologyConfig().topology();

        assertThatCode(() -> {
            declareAll(admin, declarables);
            declareAll(admin, declarables);
        }).doesNotThrowAnyException();

        connectionFactory.destroy();
    }

    private static void declareAll(RabbitAdmin admin, Declarables declarables) {
        for (Declarable declarable : declarables.getDeclarables()) {
            if (declarable instanceof Queue queue) {
                admin.declareQueue(queue);
            } else if (declarable instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (declarable instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
    }
}
