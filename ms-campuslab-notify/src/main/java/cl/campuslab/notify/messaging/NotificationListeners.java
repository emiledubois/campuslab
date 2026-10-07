package cl.campuslab.notify.messaging;

import com.rabbitmq.client.Channel;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The two consumers this slice adds (design doc §5.3) - {@code q.cmd.email} and {@code
 * q.cmd.prep}, deliberately NOT {@code q.cmd.voucher} (out of scope, §2's justification).
 * Each queue only accepts the {@code type}s that actually belong to it; anything else is
 * unrecognized for that queue (§5.3's poison-message rule). A thin pass-through to {@link
 * NotificationDeliveryHandler} (Slice B) - declaring a {@code Channel}-typed parameter is
 * what makes Spring AMQP inject the live {@code com.rabbitmq.client.Channel} for the
 * current delivery under {@code AcknowledgeMode.MANUAL}, so the handler can call {@code
 * basicAck}/{@code basicNack} itself instead of the container doing it implicitly.
 */
@Component
public class NotificationListeners {

    private static final Set<NotificationType> EMAIL_TYPES = Set.of(
            NotificationType.EMAIL_APPROVED, NotificationType.EMAIL_ROOM_READY, NotificationType.EMAIL_RETURNED);
    private static final Set<NotificationType> PREP_TYPES = Set.of(NotificationType.PREP_TICKET_REQUESTED);

    private final NotificationDeliveryHandler deliveryHandler;

    public NotificationListeners(NotificationDeliveryHandler deliveryHandler) {
        this.deliveryHandler = deliveryHandler;
    }

    @RabbitListener(queues = "q.cmd.email")
    public void onEmail(Message message, Channel channel) {
        deliveryHandler.handle(message, channel, "q.cmd.email", EMAIL_TYPES);
    }

    @RabbitListener(queues = "q.cmd.prep")
    public void onPrep(Message message, Channel channel) {
        deliveryHandler.handle(message, channel, "q.cmd.prep", PREP_TYPES);
    }
}
