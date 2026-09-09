package cl.campuslab.bookings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import cl.campuslab.bookings.domain.BookingStatus;
import cl.campuslab.bookings.service.BookingService;
import cl.campuslab.bookings.service.InvalidBookingStatusException;
import cl.campuslab.bookings.service.MalformedBookingIdException;
import cl.campuslab.bookings.web.dto.BookingResponse;
import cl.campuslab.bookings.web.dto.CreateBookingRequest;
import cl.campuslab.bookings.web.dto.UpdateBookingStatusRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Role/ownership enforcement itself is exercised end to end in
 * cl.campuslab.bookings.security.SecurityIntegrationTest and BookingApiTest; this
 * test only covers the controller's own request-shape parsing (id/status literal
 * parsing, Location header) once a caller has already passed those gates.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService service;

    @Test
    void create_returns201WithLocationHeaderPointingAtCreatedBooking() {
        BookingController controller = new BookingController(service);
        UUID id = UUID.randomUUID();
        CreateBookingRequest request = new CreateBookingRequest(
                UUID.randomUUID(), Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T12:00:00Z"), null);
        BookingResponse response = response(id, BookingStatus.SOLICITADA, 0L);
        given(service.create(eq(request), any())).willReturn(response);

        ResponseEntity<BookingResponse> result = controller.create(request, estudianteAuthentication());

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/bookings/" + id);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void get_withMalformedId_throwsBeforeCallingService() {
        BookingController controller = new BookingController(service);

        assertThatThrownBy(() -> controller.get("not-a-uuid", estudianteAuthentication()))
                .isInstanceOf(MalformedBookingIdException.class);
    }

    @Test
    void get_withValidId_delegatesToServiceWithParsedUuid() {
        BookingController controller = new BookingController(service);
        UUID id = UUID.randomUUID();
        BookingResponse response = response(id, BookingStatus.SOLICITADA, 0L);
        given(service.get(eq(id), any())).willReturn(response);

        BookingResponse result = controller.get(id.toString(), estudianteAuthentication());

        assertThat(result).isEqualTo(response);
    }

    @Test
    void list_withNoFilters_delegatesToServiceWithNullFilters() {
        BookingController controller = new BookingController(service);
        BookingResponse response = response(UUID.randomUUID(), BookingStatus.SOLICITADA, 0L);
        given(service.list(isNull(), isNull(), isNull(), any())).willReturn(List.of(response));

        List<BookingResponse> result = controller.list(null, null, null, estudianteAuthentication());

        assertThat(result).containsExactly(response);
    }

    @Test
    void list_withUnknownStatusLiteral_throwsBeforeCallingService() {
        BookingController controller = new BookingController(service);

        assertThatThrownBy(() -> controller.list("NO_EXISTE", null, null, estudianteAuthentication()))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void updateStatus_withMalformedId_throwsBeforeCallingService() {
        BookingController controller = new BookingController(service);
        UpdateBookingStatusRequest request = new UpdateBookingStatusRequest("APROBADA");

        assertThatThrownBy(() -> controller.updateStatus("not-a-uuid", request, estudianteAuthentication()))
                .isInstanceOf(MalformedBookingIdException.class);
    }

    @Test
    void updateStatus_withUnknownStatusLiteral_throwsBeforeCallingService() {
        BookingController controller = new BookingController(service);
        UpdateBookingStatusRequest request = new UpdateBookingStatusRequest("NO_EXISTE");

        assertThatThrownBy(() -> controller.updateStatus(UUID.randomUUID().toString(), request, estudianteAuthentication()))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void updateStatus_withValidIdAndStatus_delegatesToServiceWithParsedValues() {
        BookingController controller = new BookingController(service);
        UUID id = UUID.randomUUID();
        BookingResponse response = response(id, BookingStatus.APROBADA, 1L);
        given(service.updateStatus(eq(id), eq(BookingStatus.APROBADA), any())).willReturn(response);

        BookingResponse result = controller.updateStatus(id.toString(), new UpdateBookingStatusRequest("APROBADA"), estudianteAuthentication());

        assertThat(result).isEqualTo(response);
    }

    private static JwtAuthenticationToken estudianteAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("estudiante-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ESTUDIANTE")));
    }

    private static BookingResponse response(UUID id, BookingStatus status, Long version) {
        Instant now = Instant.now();
        return new BookingResponse(id, UUID.randomUUID(), "estudiante-uuid",
                Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T12:00:00Z"), null, status, version, now, now);
    }
}
