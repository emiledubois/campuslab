package cl.campuslab.mqadmin.topology;

import static org.assertj.core.api.Assertions.assertThatCode;

import cl.campuslab.mqadmin.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * AC1's "idempotent redeclare, actually re-run, not just asserted" - declaring the exact
 * same 18 declarables twice against a real broker must be a genuine no-op, never a
 * {@code 406 PRECONDITION_FAILED}. Deliberately bypasses the full Spring context (which
 * would otherwise eagerly perform a real OIDC discovery HTTP call for
 * {@code JwtDeCoderConfig} - out of scope for this class, and unavailable/undesirable in
 * a sandboxed test run) - only the piece under test, {@code RabbitTopologyConfig}'s own 18
 * explicit {@code @Bean} methods (design doc mq-names-domain-separation.md §9 AC9/AC10 -
 * the since-removed {@code topology()}/{@code Declarables} method no longer exists, so
 * this collects each bean method's return value directly), plus a hand-built {@code
 * RabbitAdmin}, are exercised. A literal process restart against infra/mq/compose.yml's
 * local profile is verified live per this task's own instructions.
 */
class RabbitTopologyConfigTest extends AbstractIntegrationTest {

    @Test
    void topology_declaredTwiceAgainstSameBroker_isIdempotent() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        List<Declarable> declarables = allDeclarables(new RabbitTopologyConfig());

        assertThatCode(() -> {
            declareAll(admin, declarables);
            declareAll(admin, declarables);
        }).doesNotThrowAnyException();

        connectionFactory.destroy();
    }

    private static List<Declarable> allDeclarables(RabbitTopologyConfig config) {
        return List.of(
                config.cmdDirectExchange(), config.cmdTopicExchange(), config.cmdDeadDlxExchange(),
                config.qCmdEmail(), config.qCmdEmailDlq(), config.qCmdPrep(), config.qCmdPrepDlq(),
                config.qCmdVoucher(), config.qCmdVoucherDlq(),
                config.bindEmailDirect(), config.bindEmailTopic(), config.bindEmailDeadLetter(),
                config.bindPrepDirect(), config.bindPrepTopic(), config.bindPrepDeadLetter(),
                config.bindVoucherDirect(), config.bindVoucherTopic(), config.bindVoucherDeadLetter());
    }

    private static void declareAll(RabbitAdmin admin, List<Declarable> declarables) {
        for (Declarable declarable : declarables) {
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
