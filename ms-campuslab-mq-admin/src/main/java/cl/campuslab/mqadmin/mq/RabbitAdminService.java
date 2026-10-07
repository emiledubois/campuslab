package cl.campuslab.mqadmin.mq;

import cl.campuslab.mqadmin.topology.MqTopology;
import java.io.IOException;
import java.util.Map;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Service Facade over {@code AmqpAdmin}/{@code RabbitAdmin} (Slice A design doc §6/§8,
 * course guide 2.3.1's {@code RabbitResourceManager} pattern) - controllers call these
 * intention-revealing methods and never see {@code Queue}/{@code Exchange}/
 * {@code Binding}/{@code AmqpAdmin} types directly. Every create/delete/purge/bind call
 * is checked first against {@link MqTopology}'s exact declared names (design doc §5.1) -
 * the same single source of truth {@code RabbitTopologyConfig} itself uses, before any
 * AMQP call is made.
 *
 * <p><b>Deviation from the design doc's literal §6 bodies, confirmed via a Testcontainers
 * probe, not assumed</b>: {@code AmqpAdmin#deleteQueue(String)}/{@code #deleteExchange
 * (String)} do NOT return {@code false} for a nonexistent target against this project's
 * RabbitMQ version - {@code queue.delete}/{@code exchange.delete} are no-ops on a missing
 * name and both always return {@code true}. Relying on the boolean return (as the design
 * doc's own §6 code literally does) would make AC2's "second DELETE on the same name
 * returns 404" and §9 A04's stated "concurrent delete race -> 404" unreachable. Existence
 * is therefore checked explicitly beforehand instead: {@code getQueueProperties} for
 * queues (already used elsewhere in this module for the same purpose), and a passive
 * {@code exchange.declare} via the shared {@code RabbitTemplate} for exchanges (AmqpAdmin
 * has no {@code getExchangeProperties}, confirmed design doc §10 open question 3).
 */
@Service
public class RabbitAdminService {

    private final RabbitAdmin rabbitAdmin;
    private final RabbitTemplate rabbitTemplate;

    public RabbitAdminService(RabbitAdmin rabbitAdmin, RabbitTemplate rabbitTemplate) {
        this.rabbitAdmin = rabbitAdmin;
        this.rabbitTemplate = rabbitTemplate;
    }

    /** Wraps AmqpAdmin#declareQueue(Queue) -> String. */
    public AdminQueueInfo createQueue(CreateQueueRequest request) {
        assertQueueNameNotManaged(request.name(), "create");
        boolean durable = request.durable() == null || request.durable();
        boolean exclusive = Boolean.TRUE.equals(request.exclusive());
        boolean autoDelete = Boolean.TRUE.equals(request.autoDelete());
        rabbitAdmin.declareQueue(new Queue(request.name(), durable, exclusive, autoDelete));
        return new AdminQueueInfo(request.name(), durable, exclusive, autoDelete, 0, 0);
    }

    /** Wraps AmqpAdmin#deleteQueue(String) -> boolean. Existence is checked explicitly via
     * getQueueProperties beforehand, not via the boolean return (see class javadoc: that
     * return is always true on this broker version, even for a nonexistent queue). */
    public void deleteQueue(String name) {
        assertQueueNameNotManaged(name, "delete");
        if (rabbitAdmin.getQueueProperties(name) == null) {
            throw new AdminResourceNotFoundException("queue", name);
        }
        rabbitAdmin.deleteQueue(name);
    }

    /** Wraps AmqpAdmin#purgeQueue(String) -> int (purged message count). Purge is guarded
     * identically to delete (design doc §3.4/§5.1 - a deliberate scope extension beyond the
     * literal "delete only" acceptance criteria, flagged for reviewer confirmation). */
    public PurgeResult purgeQueue(String name) {
        assertQueueNameNotManaged(name, "purge");
        try {
            return new PurgeResult(name, rabbitAdmin.purgeQueue(name));
        } catch (AmqpException broker404OrSimilar) {
            throw new AdminResourceNotFoundException("queue", name);
        }
    }

    /** Wraps AmqpAdmin#declareExchange(Exchange) -> void. Concrete Exchange subtype chosen
     *  by a small switch on 'type' (Spring AMQP's own DirectExchange/TopicExchange/
     *  FanoutExchange/HeadersExchange constructors), mirroring how RabbitTopologyConfig
     *  already constructs DirectExchange/TopicExchange concretely - just parameterized by
     *  request input instead of hardcoded. */
    public AdminExchangeInfo createExchange(CreateExchangeRequest request) {
        assertExchangeNameNotManaged(request.name(), "create");
        boolean durable = request.durable() == null || request.durable();
        boolean autoDelete = Boolean.TRUE.equals(request.autoDelete());
        rabbitAdmin.declareExchange(buildExchange(request.name(), request.type(), durable, autoDelete));
        return new AdminExchangeInfo(request.name(), request.type(), durable, autoDelete);
    }

    /** Wraps AmqpAdmin#deleteExchange(String) -> boolean. Existence is checked explicitly
     * via a passive exchange.declare beforehand, not via the boolean return (see class
     * javadoc: AmqpAdmin exposes no getExchangeProperties, and the boolean return is
     * always true on this broker version, even for a nonexistent exchange). */
    public void deleteExchange(String name) {
        assertExchangeNameNotManaged(name, "delete");
        if (!exchangeExists(name)) {
            throw new AdminResourceNotFoundException("exchange", name);
        }
        rabbitAdmin.deleteExchange(name);
    }

    private boolean exchangeExists(String name) {
        try {
            return Boolean.TRUE.equals(rabbitTemplate.execute(channel -> {
                try {
                    channel.exchangeDeclarePassive(name);
                    return true;
                } catch (IOException notFound) {
                    return false;
                }
            }));
        } catch (AmqpException channelClosedOrSimilar) {
            return false;
        }
    }

    /** Wraps AmqpAdmin#declareBinding(Binding) -> void. Pre-checks queue-destination
     *  existence via getQueueProperties - the exchange-destination case cannot be cheaply
     *  pre-checked (no getExchangeProperties in AmqpAdmin), accepted limitation per design
     *  doc §10 open question 3; such a bind fails at the broker and surfaces as 503 here. */
    public AdminBindingInfo createBinding(BindingRequest request) {
        assertBindingNotManaged(request, "create");
        if ("QUEUE".equals(request.destinationType()) && rabbitAdmin.getQueueProperties(request.destination()) == null) {
            throw new AdminResourceNotFoundException("queue", request.destination());
        }
        rabbitAdmin.declareBinding(buildBinding(request));
        return new AdminBindingInfo(request.source(), request.destination(), request.destinationType(),
                request.routingKey() == null ? "" : request.routingKey());
    }

    /** Wraps AmqpAdmin#removeBinding(Binding) -> void (no-op if the binding never existed -
     * AMQP's queue.unbind/exchange.unbind completes normally even if the binding was never
     * there, standard protocol behaviour, design doc §3.8). */
    public void deleteBinding(BindingRequest request) {
        assertBindingNotManaged(request, "delete");
        rabbitAdmin.removeBinding(buildBinding(request));
    }

    /** Wraps AmqpAdmin#getQueueInfo(String) -> QueueInformation. Included for fidelity with
     *  the course guide's named method set; not invoked by any endpoint this slice (no
     *  "GET single queue" endpoint was requested) - available for a future slice. */
    public AdminQueueInfo getQueueInfo(String name) {
        QueueInformation info = rabbitAdmin.getQueueInfo(name);
        if (info == null) {
            throw new AdminResourceNotFoundException("queue", name);
        }
        // durable/exclusive/autoDelete are not exposed by QueueInformation (confirmed via
        // javap: constructor is (String, int, int) only) - acceptable since this method is
        // not surfaced by a public endpoint this slice.
        return new AdminQueueInfo(info.getName(), true, false, false, info.getMessageCount(), info.getConsumerCount());
    }

    private Exchange buildExchange(String name, String type, boolean durable, boolean autoDelete) {
        return switch (type) {
            case "direct" -> new DirectExchange(name, durable, autoDelete);
            case "topic" -> new TopicExchange(name, durable, autoDelete);
            case "fanout" -> new FanoutExchange(name, durable, autoDelete);
            case "headers" -> new HeadersExchange(name, durable, autoDelete);
            default -> throw new IllegalStateException("unreachable: 'type' is already validated by @Pattern");
        };
    }

    private Binding buildBinding(BindingRequest request) {
        Binding.DestinationType destinationType = "QUEUE".equals(request.destinationType())
                ? Binding.DestinationType.QUEUE : Binding.DestinationType.EXCHANGE;
        String routingKey = request.routingKey() == null ? "" : request.routingKey();
        // Binding(String destination, DestinationType destinationType, String exchange,
        //         String routingKey, Map<String,Object> arguments) - exact constructor order
        // confirmed via javap against the resolved spring-amqp artifact.
        return new Binding(request.destination(), destinationType, request.source(), routingKey, Map.of());
    }

    private void assertQueueNameNotManaged(String name, String operation) {
        if (MqTopology.isManagedQueue(name)) {
            throw new ProtectedTopologyResourceException("queue", name, operation);
        }
    }

    private void assertExchangeNameNotManaged(String name, String operation) {
        if (MqTopology.isManagedExchange(name)) {
            throw new ProtectedTopologyResourceException("exchange", name, operation);
        }
    }

    private void assertBindingNotManaged(BindingRequest request, String operation) {
        boolean touchesManagedSource = MqTopology.isManagedExchange(request.source());
        boolean touchesManagedQueueDestination =
                "QUEUE".equals(request.destinationType()) && MqTopology.isManagedQueue(request.destination());
        boolean touchesManagedExchangeDestination =
                "EXCHANGE".equals(request.destinationType()) && MqTopology.isManagedExchange(request.destination());
        if (touchesManagedSource || touchesManagedQueueDestination || touchesManagedExchangeDestination) {
            throw new ProtectedTopologyResourceException(
                    "binding", request.source() + " -> " + request.destination(), operation);
        }
    }
}
