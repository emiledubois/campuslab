package cl.campuslab.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingRepository;
import cl.campuslab.bookings.domain.BookingStatus;
import cl.campuslab.bookings.web.dto.BookingResponse;
import cl.campuslab.bookings.web.dto.CreateBookingRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository repository;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(repository);
    }

    @Test
    void create_withValidRequest_setsServerAssignedStudentSubAndStatus() {
        Instant start = Instant.parse("2026-09-15T10:00:00Z");
        Instant end = Instant.parse("2026-09-15T12:00:00Z");
        CreateBookingRequest request = new CreateBookingRequest(UUID.randomUUID(), start, end, "notes");
        Booking saved = withId(new Booking(request.resourceId(), "estudiante-uuid", start, end, "notes"));
        given(repository.save(any(Booking.class))).willReturn(saved);

        BookingResponse response = service.create(request, estudianteAuthentication("estudiante-uuid"));

        assertThat(response.studentSub()).isEqualTo("estudiante-uuid");
        assertThat(response.status()).isEqualTo(BookingStatus.SOLICITADA);
    }

    @Test
    void create_withEndNotAfterStart_throwsInvalidWindow() {
        Instant start = Instant.parse("2026-09-15T10:00:00Z");
        CreateBookingRequest request = new CreateBookingRequest(UUID.randomUUID(), start, start, "notes");

        assertThatThrownBy(() -> service.create(request, estudianteAuthentication("estudiante-uuid")))
                .isInstanceOf(InvalidBookingWindowException.class);
    }

    @Test
    void get_ownedByCaller_returnsBooking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        BookingResponse response = service.get(id, estudianteAuthentication("estudiante-uuid"));

        assertThat(response.studentSub()).isEqualTo("estudiante-uuid");
    }

    @Test
    void get_ownedByAnotherStudent_throwsNotFoundMasking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("other-student-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.get(id, estudianteAuthentication("estudiante-uuid")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void get_asTecnico_returnsAnyBookingRegardlessOfOwner() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("some-student-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        BookingResponse response = service.get(id, staffAuthentication("tecnico-uuid", "TECNICO"));

        assertThat(response.studentSub()).isEqualTo("some-student-uuid");
    }

    @Test
    void get_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, staffAuthentication("tecnico-uuid", "TECNICO")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void updateStatus_tecnicoApprovingSolicitada_returnsApproved() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(repository.saveAndFlush(booking)).willReturn(booking);

        BookingResponse response = service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-uuid", "TECNICO"));

        assertThat(response.status()).isEqualTo(BookingStatus.APROBADA);
        verify(repository).saveAndFlush(booking);
    }

    @Test
    void updateStatus_calledByAnotherStudent_throwsNotFoundMasking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("owner-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("intruder-uuid")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void updateStatus_estudianteRequestingNonCancelTarget_throwsTransitionNotPermitted() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, estudianteAuthentication("estudiante-uuid")))
                .isInstanceOf(BookingTransitionNotPermittedException.class);
    }

    @Test
    void updateStatus_estudianteCancellingFromSolicitada_returnsCancelled() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(repository.saveAndFlush(booking)).willReturn(booking);

        BookingResponse response = service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("estudiante-uuid"));

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELADA);
    }

    @Test
    void updateStatus_estudianteCancellingFromEnPreparacion_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.EN_PREPARACION));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("estudiante-uuid")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_tecnicoSkippingForwardToEnUso_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.EN_USO, staffAuthentication("tecnico-uuid", "TECNICO")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_tecnicoActingOnTerminalDevuelta_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-uuid", BookingStatus.DEVUELTA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-uuid", "TECNICO")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-uuid", "TECNICO")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_asEstudiante_scopesToOwnStudentSub() {
        given(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .willReturn(List.of(withId(bookingOf("estudiante-uuid", BookingStatus.SOLICITADA))));

        List<BookingResponse> responses = service.list(null, null, null, estudianteAuthentication("estudiante-uuid"));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).studentSub()).isEqualTo("estudiante-uuid");
    }

    private static Booking bookingOf(String studentSub, BookingStatus status) {
        Booking booking = new Booking(
                UUID.randomUUID(), studentSub, Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T12:00:00Z"), null);
        setField(booking, "status", status);
        return booking;
    }

    private static JwtAuthenticationToken estudianteAuthentication(String subject) {
        return staffAuthentication(subject, "ESTUDIANTE");
    }

    private static JwtAuthenticationToken staffAuthentication(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** Test-only reflection helpers - Booking's id/status/version are Hibernate-managed with no public setter. */
    private static Booking withId(Booking booking) {
        setField(booking, "id", UUID.randomUUID());
        return booking;
    }

    private static void setField(Booking booking, String fieldName, Object value) {
        try {
            Field field = Booking.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(booking, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
