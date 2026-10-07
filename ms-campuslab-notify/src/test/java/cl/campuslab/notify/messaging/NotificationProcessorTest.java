package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AAA unit coverage of the parse/validate/dedup pipeline (design doc §5.3/§7.2) - the
 * actual @RabbitListener wiring (ack/nack, retry, DLQ routing) is proven against a real
 * broker by NotificationListenersIntegrationTest (AC1-AC7/AC9), since that behaviour
 * genuinely needs a real container, not a mock. {@code dispatcher} is mocked here with
 * its default (never-throws) behaviour - the retry decision itself is
 * NotificationDeliveryHandlerTest's concern (AC10), not this class's.
 */
@ExtendWith(MockitoExtension.class)
class NotificationProcessorTest {

    private static final Set<NotificationType> EMAIL_TYPES = Set.of(
            NotificationType.EMAIL_APPROVED, NotificationType.EMAIL_ROOM_READY, NotificationType.EMAIL_RETURNED);
    private static final Set<NotificationType> PREP_TYPES = Set.of(NotificationType.PREP_TICKET_REQUESTED);

    @Mock
    private DedupCache dedupCache;

    @Mock
    private NotificationDispatcher dispatcher;

    private NotificationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationProcessor(new ObjectMapper(), dedupCache, dispatcher);
    }

    @Test
    void process_withWellFormedFreshEmailEnvelope_marksProcessedExactlyOnce() {
        given(dedupCache.isDuplicate("event-1")).willReturn(false);
        byte[] body = validEnvelope("EMAIL_APPROVED", "event-1");

        String correlationId = processor.process(body, "q.cmd.email", EMAIL_TYPES);

        verify(dedupCache, times(1)).markProcessed("event-1");
        verify(dispatcher, times(1)).dispatch(NotificationType.EMAIL_APPROVED,
                new NotificationEnvelope("EMAIL_APPROVED", "event-1", "2026-09-10T12:00:00Z", "trace-1", "booking-1",
                        Map.of("bookingId", "booking-1", "studentOid", "student-oid-1")));
        assertThat(correlationId).isEqualTo("booking-1");
    }

    @Test
    void process_withDuplicateEventId_neverMarksProcessedAgain() {
        given(dedupCache.isDuplicate("event-1")).willReturn(true);
        byte[] body = validEnvelope("EMAIL_APPROVED", "event-1");

        processor.process(body, "q.cmd.email", EMAIL_TYPES);

        verify(dedupCache, never()).markProcessed("event-1");
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void process_withNotValidJson_throwsPoisonUnparseableEnvelope() {
        byte[] body = "not json at all".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(body, "q.cmd.email", EMAIL_TYPES))
                .isInstanceOf(PoisonMessageException.class)
                .extracting(ex -> ((PoisonMessageException) ex).reason())
                .isEqualTo(PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE);
    }

    @Test
    void process_withMissingRequiredField_throwsPoisonUnparseableEnvelope() {
        byte[] body = ("{\"type\":\"EMAIL_APPROVED\",\"timestamp\":\"2026-09-10T12:00:00Z\","
                + "\"traceId\":\"t1\",\"correlationId\":\"c1\"}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(body, "q.cmd.email", EMAIL_TYPES))
                .isInstanceOf(PoisonMessageException.class)
                .extracting(ex -> ((PoisonMessageException) ex).reason())
                .isEqualTo(PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE);
    }

    @Test
    void process_withUnrecognizedType_throwsPoisonUnrecognizedType() {
        byte[] body = validEnvelope("FOO", "event-2");

        assertThatThrownBy(() -> processor.process(body, "q.cmd.prep", PREP_TYPES))
                .isInstanceOf(PoisonMessageException.class)
                .extracting(ex -> ((PoisonMessageException) ex).reason())
                .isEqualTo(PoisonMessageException.Reason.UNRECOGNIZED_TYPE);
    }

    @Test
    void process_withTypeNotAllowedOnThisQueue_throwsPoisonUnrecognizedType() {
        // PREP_TICKET_REQUESTED is a real, recognized type overall - just never valid on
        // q.cmd.email (design doc §5.3's per-queue allowed-type scoping).
        byte[] body = validEnvelope("PREP_TICKET_REQUESTED", "event-3");

        assertThatThrownBy(() -> processor.process(body, "q.cmd.email", EMAIL_TYPES))
                .isInstanceOf(PoisonMessageException.class)
                .extracting(ex -> ((PoisonMessageException) ex).reason())
                .isEqualTo(PoisonMessageException.Reason.UNRECOGNIZED_TYPE);
    }

    private static byte[] validEnvelope(String type, String eventId) {
        String json = """
                {"type":"%s","eventId":"%s","timestamp":"2026-09-10T12:00:00Z","traceId":"trace-1",
                "correlationId":"booking-1","payload":{"bookingId":"booking-1","studentOid":"student-oid-1"}}
                """.formatted(type, eventId);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
