package cl.campuslab.notify.messaging;

import com.rabbitmq.client.Channel;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The prep domain's sole consumer (design doc mq-names-domain-separation.md §9 AC5/AC6,
 * split out of the former {@code NotificationListeners} per indicator 4's "separacion
 * clara por dominios funcionales"). Shares the unmodified Slice-B {@link
 * NotificationDeliveryHandler} - no retry/ack/backoff logic is duplicated here, only the
 * thin {@code @RabbitListener} entry point and this domain's own allow-list move. The
 * {@code queues} attribute is a property placeholder (design doc §6 Decision 2), resolved
 * against the same {@code notify.messaging.queue.prep} key {@link
 * NotifyQueueNamesProperties} also binds - never a literal, never SpEL bean navigation.
 */
@Component
public class PrepNotificationListener {

    private static final Set<NotificationType> PREP_TYPES = Set.of(NotificationType.PREP_TICKET_REQUESTED);

    private final NotificationDeliveryHandler deliveryHandler;
    private final NotifyQueueNamesProperties queueNames;

    public PrepNotificationListener(NotificationDeliveryHandler deliveryHandler, NotifyQueueNamesProperties queueNames) {
        this.deliveryHandler = deliveryHandler;
        this.queueNames = queueNames;
    }

    @RabbitListener(queues = "${notify.messaging.queue.prep}")
    public void onPrep(Message message, Channel channel) {
        deliveryHandler.handle(message, channel, queueNames.prep(), PREP_TYPES);
    }
}
