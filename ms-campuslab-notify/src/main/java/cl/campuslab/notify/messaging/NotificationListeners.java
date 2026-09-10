package cl.campuslab.notify.messaging;

import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The two consumers this slice adds (design doc §5.3) - {@code q.cmd.email} and {@code
 * q.cmd.prep}, deliberately NOT {@code q.cmd.voucher} (out of scope, §2's justification).
 * Each queue only accepts the {@code type}s that actually belong to it; anything else is
 * unrecognized for that queue (§5.3's poison-message rule). Accepts the raw AMQP {@link
 * Message} rather than a converted/typed parameter so envelope parsing is fully under
 * {@link NotificationProcessor}'s own control - a malformed body never throws from
 * Spring AMQP's own message-conversion machinery before this class gets a chance to
 * classify it as {@code UNPARSEABLE_ENVELOPE} vs. letting an unrelated exception type
 * escape uncaught.
 */
@Component
public class NotificationListeners {

    private static final Set<NotificationType> EMAIL_TYPES = Set.of(
            NotificationType.EMAIL_APPROVED, NotificationType.EMAIL_ROOM_READY, NotificationType.EMAIL_RETURNED);
    private static final Set<NotificationType> PREP_TYPES = Set.of(NotificationType.PREP_TICKET_REQUESTED);

    private final NotificationProcessor processor;

    public NotificationListeners(NotificationProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = "q.cmd.email")
    public void onEmail(Message message) {
        processor.process(message.getBody(), "q.cmd.email", EMAIL_TYPES);
    }

    @RabbitListener(queues = "q.cmd.prep")
    public void onPrep(Message message) {
        processor.process(message.getBody(), "q.cmd.prep", PREP_TYPES);
    }
}
