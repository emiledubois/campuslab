package cl.campuslab.mqadmin.mq;

import cl.campuslab.mqadmin.topology.MqTopology;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * The guarded requeue-from-DLQ operation (design doc §3) - {@code dlqName} is validated
 * against a static allow-list before anything else touches RabbitMQ (A03/A04), and the
 * request body must state exactly {@code count} or {@code all}, never a silent default
 * (design doc §10 open question 3 - no second confirmation step, this explicit-ask-plus-
 * audit-log IS the guard). Moves messages one at a time via {@code basicGet}/{@code
 * basicPublish}/{@code basicAck}: a message is only acked off the DLQ once its republish
 * onto {@code cmd.direct} has not thrown, so a republish failure leaves it safely nacked
 * back onto the DLQ (requeue=true) rather than lost.
 */
@Service
public class RequeueService {

    private static final Logger log = LoggerFactory.getLogger(RequeueService.class);

    private final RabbitTemplate rabbitTemplate;
    private final QueueInspectionService inspectionService;

    public RequeueService(RabbitTemplate rabbitTemplate, QueueInspectionService inspectionService) {
        this.rabbitTemplate = rabbitTemplate;
        this.inspectionService = inspectionService;
    }

    public RequeueResponse requeue(String dlqName, RequeueRequest request, String callerOid) {
        validateDlqName(dlqName);
        validateRequestShape(request);

        long depthAtStart = inspectionService.depthOf(dlqName);
        int moveLimit = request.count() != null ? request.count() : (int) Math.min(depthAtStart, Integer.MAX_VALUE);

        int actualMoved = rabbitTemplate.execute(channel -> doRequeue(channel, dlqName, moveLimit));
        long remainingInDlq = inspectionService.depthOf(dlqName);

        log.info("Requeue invoked: oid=[{}] dlqName=[{}] requestedCount=[{}] actualRequeuedCount=[{}]",
                callerOid, dlqName, moveLimit, actualMoved);
        return new RequeueResponse(dlqName, moveLimit, actualMoved, remainingInDlq);
    }

    private int doRequeue(Channel channel, String dlqName, int maxToMove) throws Exception {
        String routingKey = MqTopology.DLQ_ORIGINAL_ROUTING_KEY.get(dlqName);
        int moved = 0;
        for (int i = 0; i < maxToMove; i++) {
            GetResponse response = channel.basicGet(dlqName, false);
            if (response == null) {
                break;
            }
            long deliveryTag = response.getEnvelope().getDeliveryTag();
            try {
                channel.basicPublish(MqTopology.EXCHANGE_CMD_DIRECT, routingKey, response.getProps(), response.getBody());
                channel.basicAck(deliveryTag, false);
                moved++;
            } catch (Exception republishFailure) {
                channel.basicNack(deliveryTag, false, true);
                log.error("Requeue republish failed: dlqName=[{}] reason=[{}]", dlqName, republishFailure.getMessage());
                break;
            }
        }
        return moved;
    }

    private static void validateDlqName(String dlqName) {
        if (!MqTopology.DLQ_NAMES.contains(dlqName)) {
            throw new UnknownDlqException(dlqName);
        }
    }

    private static void validateRequestShape(RequeueRequest request) {
        Integer count = request != null ? request.count() : null;
        Boolean all = request != null ? request.all() : null;
        boolean hasCount = count != null;
        boolean hasAll = all != null;
        if (hasCount == hasAll) {
            throw new InvalidRequeueRequestException("Exactly one of 'count' or 'all' must be provided.");
        }
        if (hasCount && count <= 0) {
            throw new InvalidRequeueRequestException("'count' must be greater than zero.");
        }
        if (hasAll && !Boolean.TRUE.equals(all)) {
            throw new InvalidRequeueRequestException("'all' must be true when provided.");
        }
    }
}
