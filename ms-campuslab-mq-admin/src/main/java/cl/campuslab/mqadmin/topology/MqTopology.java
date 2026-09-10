package cl.campuslab.mqadmin.topology;

import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every exchange/queue/routing-key name this slice touches
 * (design doc §5.1) - referenced by {@link RabbitTopologyConfig} (declaration), the
 * inspection service, and the requeue service, so the three never risk drifting apart on
 * a typo'd literal. Per the design doc's own "permanent from the moment this ships" note:
 * these values must never change in place once deployed with real data in the queues.
 */
public final class MqTopology {

    public static final String EXCHANGE_CMD_DIRECT = "cmd.direct";
    public static final String EXCHANGE_CMD_TOPIC = "cmd.topic";
    public static final String EXCHANGE_CMD_DEAD_DLX = "cmd.dead.dlx";

    public static final String QUEUE_EMAIL = "q.cmd.email";
    public static final String QUEUE_PREP = "q.cmd.prep";
    public static final String QUEUE_VOUCHER = "q.cmd.voucher";

    public static final String DLQ_EMAIL = "q.cmd.email.dlq";
    public static final String DLQ_PREP = "q.cmd.prep.dlq";
    public static final String DLQ_VOUCHER = "q.cmd.voucher.dlq";

    /** The 6 declared queues, in the exact order the API contract's example lists them. */
    public static final List<String> ALL_QUEUES_IN_DISPLAY_ORDER = List.of(
            QUEUE_EMAIL, DLQ_EMAIL, QUEUE_PREP, DLQ_PREP, QUEUE_VOUCHER, DLQ_VOUCHER);

    public static final List<String> DLQ_NAMES = List.of(DLQ_EMAIL, DLQ_PREP, DLQ_VOUCHER);

    /** Direct-exchange routing key each work queue is bound to (design doc §5.1 table). */
    public static final Map<String, String> WORK_QUEUE_DIRECT_ROUTING_KEY = Map.of(
            QUEUE_EMAIL, "email.send",
            QUEUE_PREP, "prep.ticket",
            QUEUE_VOUCHER, "voucher.gen");

    /** Topic-exchange binding pattern each work queue is bound to (design doc §5.1 table). */
    public static final Map<String, String> WORK_QUEUE_TOPIC_BINDING_PATTERN = Map.of(
            QUEUE_EMAIL, "email.*",
            QUEUE_PREP, "prep.#",
            QUEUE_VOUCHER, "voucher.*");

    /** Dead-letter routing key each work queue's {@code x-dead-letter-routing-key} argument
     * uses, and the exact key its DLQ is bound to on {@code cmd.dead.dlx} (design doc §5.1). */
    public static final Map<String, String> WORK_QUEUE_DEAD_LETTER_ROUTING_KEY = Map.of(
            QUEUE_EMAIL, "email.dlq",
            QUEUE_PREP, "prep.dlq",
            QUEUE_VOUCHER, "voucher.dlq");

    public static final Map<String, String> DLQ_TO_WORK_QUEUE = Map.of(
            DLQ_EMAIL, QUEUE_EMAIL,
            DLQ_PREP, QUEUE_PREP,
            DLQ_VOUCHER, QUEUE_VOUCHER);

    /** Requeue target: republish onto {@code cmd.direct} with the DLQ's own original
     * routing key (design doc §3's requeue behaviour), never the dead-letter routing key. */
    public static final Map<String, String> DLQ_ORIGINAL_ROUTING_KEY = Map.of(
            DLQ_EMAIL, "email.send",
            DLQ_PREP, "prep.ticket",
            DLQ_VOUCHER, "voucher.gen");

    private MqTopology() {
    }

    public static boolean isDlq(String queueName) {
        return DLQ_NAMES.contains(queueName);
    }
}
