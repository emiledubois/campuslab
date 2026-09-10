package cl.campuslab.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cl.campuslab.bookings.catalog.CatalogDecrementOutcome;
import cl.campuslab.bookings.catalog.CatalogStockClient;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository repository;

    @Mock
    private CatalogStockClient catalogStockClient;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(repository, catalogStockClient);
    }

    @Test
    void create_withValidRequest_setsServerAssignedStudentOidAndStatus() {
        Instant start = Instant.parse("2026-09-15T10:00:00Z");
        Instant end = Instant.parse("2026-09-15T12:00:00Z");
        CreateBookingRequest request = new CreateBookingRequest(UUID.randomUUID(), start, end, "notes");
        Booking saved = withId(new Booking(request.resourceId(), "estudiante-oid", start, end, "notes"));
        given(repository.save(any(Booking.class))).willReturn(saved);

        BookingResponse response = service.create(request, estudianteAuthentication("estudiante-oid"));

        assertThat(response.studentOid()).isEqualTo("estudiante-oid");
        assertThat(response.status()).isEqualTo(BookingStatus.SOLICITADA);
    }

    @Test
    void create_withEndNotAfterStart_throwsInvalidWindow() {
        Instant start = Instant.parse("2026-09-15T10:00:00Z");
        CreateBookingRequest request = new CreateBookingRequest(UUID.randomUUID(), start, start, "notes");

        assertThatThrownBy(() -> service.create(request, estudianteAuthentication("estudiante-oid")))
                .isInstanceOf(InvalidBookingWindowException.class);
    }

    @Test
    void create_withMissingOidClaim_throwsMissingOwnerOidNeverFallingBackToSub() {
        Instant start = Instant.parse("2026-09-15T10:00:00Z");
        Instant end = Instant.parse("2026-09-15T12:00:00Z");
        CreateBookingRequest request = new CreateBookingRequest(UUID.randomUUID(), start, end, "notes");

        assertThatThrownBy(() -> service.create(request, estudianteAuthenticationWithNoOid("estudiante-sub")))
                .isInstanceOf(MissingOwnerOidException.class);
    }

    @Test
    void get_ownedByCaller_returnsBooking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        BookingResponse response = service.get(id, estudianteAuthentication("estudiante-oid"));

        assertThat(response.studentOid()).isEqualTo("estudiante-oid");
    }

    @Test
    void get_ownedByAnotherStudent_throwsNotFoundMasking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("other-student-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.get(id, estudianteAuthentication("estudiante-oid")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void get_callerSubEqualsOwnersOid_isNotTreatedAsOwner() {
        // Deliberately confusing fixture (design doc §9 AC11): the intruder's *sub* equals
        // the real owner's *oid* - proves the ownership check reads oid, not sub, under a
        // different variable name.
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("owner-oid-value", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        JwtAuthenticationToken intruder = jwtAuthentication("owner-oid-value", "intruder-different-oid", "ESTUDIANTE");

        assertThatThrownBy(() -> service.get(id, intruder)).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void get_asTecnico_returnsAnyBookingRegardlessOfOwner() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("some-student-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        BookingResponse response = service.get(id, staffAuthentication("tecnico-oid", "TECNICO"));

        assertThat(response.studentOid()).isEqualTo("some-student-oid");
    }

    @Test
    void get_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void get_estudianteWithMissingOidClaim_throwsMissingOwnerOid() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.get(id, estudianteAuthenticationWithNoOid("estudiante-sub")))
                .isInstanceOf(MissingOwnerOidException.class);
    }

    @Test
    void updateStatus_tecnicoApprovingSolicitada_returnsApproved() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.SUCCESS);
        given(repository.saveAndFlush(booking)).willReturn(booking);

        BookingResponse response = service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO"));

        assertThat(response.status()).isEqualTo(BookingStatus.APROBADA);
        verify(repository).saveAndFlush(booking);
    }

    @Test
    void updateStatus_tecnicoWithNoOidClaim_stillSucceeds() {
        // Staff have no ownership dimension - a missing oid must not block a legitimate
        // status change (only ESTUDIANTE's ownership path is strict about oid).
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.SUCCESS);
        given(repository.saveAndFlush(booking)).willReturn(booking);
        JwtAuthenticationToken tecnicoWithNoOid = staffAuthenticationWithNoOid("tecnico-sub", "TECNICO");

        BookingResponse response = service.updateStatus(id, BookingStatus.APROBADA, tecnicoWithNoOid);

        assertThat(response.status()).isEqualTo(BookingStatus.APROBADA);
    }

    @Test
    void updateStatus_approvalWithCatalogInsufficientStock_throwsAndNeverWritesLocally() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.INSUFFICIENT_STOCK);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(CatalogInsufficientStockException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_approvalWithCatalogResourceNotFound_throwsConflictNeverFourOhFour() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.NOT_FOUND);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(CatalogResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_approvalWithCatalogUnreachable_throwsServiceUnavailable() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.UNREACHABLE);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(CatalogServiceUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_approvalWithCatalogUnexpectedError_throwsServiceUnavailable() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.UNEXPECTED_ERROR);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(CatalogServiceUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_approvalWithLocalOptimisticLockLoss_reReadShowsApproved_skipsCompensation() {
        // Revision 1 (design doc §3 step 2/§9 AC7a): the common case - a concurrent
        // duplicate approval of this SAME booking won the local race. Re-reading finds
        // the booking already APROBADA, so the one physical decrement legitimately
        // belongs to it and must NOT be reversed - deterministic proof of the
        // disambiguation branch, independent of catalog's real timing.
        UUID resourceId = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA, resourceId));
        UUID id = booking.getId();
        Booking reReadAfterRace = bookingOf("estudiante-oid", BookingStatus.APROBADA, resourceId);
        setField(reReadAfterRace, "id", id);
        given(repository.findById(id)).willReturn(Optional.of(booking), Optional.of(reReadAfterRace));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.SUCCESS);
        given(repository.saveAndFlush(booking))
                .willThrow(new ObjectOptimisticLockingFailureException(Booking.class, id));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(catalogStockClient, never()).increment(any(), any(), any());
    }

    @Test
    void updateStatus_approvalWithLocalOptimisticLockLoss_reReadShowsDifferentTransition_compensatesThenRethrows() {
        // Revision 1 (design doc §3 step 2/§9 AC7b): a genuinely different concurrent
        // transition won (e.g. a concurrent cancel) - re-reading finds a non-APROBADA
        // status, so no approval survives to own the decrement and compensation is
        // still correct.
        UUID resourceId = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA, resourceId));
        UUID id = booking.getId();
        Booking reReadAfterRace = bookingOf("estudiante-oid", BookingStatus.CANCELADA, resourceId);
        setField(reReadAfterRace, "id", id);
        given(repository.findById(id)).willReturn(Optional.of(booking), Optional.of(reReadAfterRace));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.SUCCESS);
        given(repository.saveAndFlush(booking))
                .willThrow(new ObjectOptimisticLockingFailureException(Booking.class, id));
        given(catalogStockClient.increment(any(), any(), any())).willReturn(true);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(catalogStockClient, times(1)).increment(eq(resourceId), eq(id), any());
    }

    @Test
    void updateStatus_approvalWithLocalOptimisticLockLossAndCompensationCallFails_stillRethrowsConflict() {
        // The compensating call itself failing (catalog unreachable at that exact moment)
        // must still surface the same 409 to the caller - the booking's local state
        // is correct either way (design doc §3 step 2/§10 Open Question 1).
        UUID resourceId = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA, resourceId));
        UUID id = booking.getId();
        Booking reReadAfterRace = bookingOf("estudiante-oid", BookingStatus.CANCELADA, resourceId);
        setField(reReadAfterRace, "id", id);
        given(repository.findById(id)).willReturn(Optional.of(booking), Optional.of(reReadAfterRace));
        given(catalogStockClient.decrement(any(), any(), any())).willReturn(CatalogDecrementOutcome.SUCCESS);
        given(repository.saveAndFlush(booking))
                .willThrow(new ObjectOptimisticLockingFailureException(Booking.class, id));
        given(catalogStockClient.increment(any(), any(), any())).willReturn(false);

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(catalogStockClient).increment(eq(resourceId), eq(id), any());
    }

    @Test
    void updateStatus_calledByAnotherStudent_throwsNotFoundMasking() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("owner-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("intruder-oid")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void updateStatus_estudianteWithMissingOidClaim_throwsMissingOwnerOid() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthenticationWithNoOid("estudiante-sub")))
                .isInstanceOf(MissingOwnerOidException.class);
    }

    @Test
    void updateStatus_estudianteRequestingNonCancelTarget_throwsTransitionNotPermitted() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, estudianteAuthentication("estudiante-oid")))
                .isInstanceOf(BookingTransitionNotPermittedException.class);
    }

    @Test
    void updateStatus_estudianteCancellingFromSolicitada_returnsCancelled() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));
        given(repository.saveAndFlush(booking)).willReturn(booking);

        BookingResponse response = service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("estudiante-oid"));

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELADA);
    }

    @Test
    void updateStatus_estudianteCancellingFromEnPreparacion_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.EN_PREPARACION));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.CANCELADA, estudianteAuthentication("estudiante-oid")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_tecnicoSkippingForwardToEnUso_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.EN_USO, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_tecnicoActingOnTerminalDevuelta_throwsIllegalTransition() {
        UUID id = UUID.randomUUID();
        Booking booking = withId(bookingOf("estudiante-oid", BookingStatus.DEVUELTA));
        given(repository.findById(id)).willReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(IllegalBookingTransitionException.class);
    }

    @Test
    void updateStatus_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(id, BookingStatus.APROBADA, staffAuthentication("tecnico-oid", "TECNICO")))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_asEstudiante_scopesToOwnStudentOid() {
        given(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .willReturn(List.of(withId(bookingOf("estudiante-oid", BookingStatus.SOLICITADA))));

        List<BookingResponse> responses = service.list(null, null, null, estudianteAuthentication("estudiante-oid"));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).studentOid()).isEqualTo("estudiante-oid");
    }

    @Test
    void list_estudianteWithMissingOidClaim_throwsMissingOwnerOid() {
        assertThatThrownBy(() -> service.list(null, null, null, estudianteAuthenticationWithNoOid("estudiante-sub")))
                .isInstanceOf(MissingOwnerOidException.class);
    }

    private static Booking bookingOf(String studentOid, BookingStatus status) {
        return bookingOf(studentOid, status, UUID.randomUUID());
    }

    private static Booking bookingOf(String studentOid, BookingStatus status, UUID resourceId) {
        Booking booking = new Booking(
                resourceId, studentOid, Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T12:00:00Z"), null);
        setField(booking, "status", status);
        return booking;
    }

    private static JwtAuthenticationToken estudianteAuthentication(String oid) {
        return jwtAuthentication(oid, oid, "ESTUDIANTE");
    }

    private static JwtAuthenticationToken staffAuthentication(String oid, String role) {
        return jwtAuthentication(oid, oid, role);
    }

    private static JwtAuthenticationToken jwtAuthentication(String subject, String oid, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("oid", oid)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static JwtAuthenticationToken estudianteAuthenticationWithNoOid(String subject) {
        return authenticationWithNoOid(subject, "ESTUDIANTE");
    }

    private static JwtAuthenticationToken staffAuthenticationWithNoOid(String subject, String role) {
        return authenticationWithNoOid(subject, role);
    }

    private static JwtAuthenticationToken authenticationWithNoOid(String subject, String role) {
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
