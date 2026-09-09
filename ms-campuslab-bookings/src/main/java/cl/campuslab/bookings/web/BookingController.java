package cl.campuslab.bookings.web;

import cl.campuslab.bookings.domain.BookingStatus;
import cl.campuslab.bookings.service.BookingService;
import cl.campuslab.bookings.service.InvalidBookingQueryException;
import cl.campuslab.bookings.service.InvalidBookingStatusException;
import cl.campuslab.bookings.service.MalformedBookingIdException;
import cl.campuslab.bookings.web.dto.BookingResponse;
import cl.campuslab.bookings.web.dto.CreateBookingRequest;
import cl.campuslab.bookings.web.dto.UpdateBookingStatusRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in path rules in SecurityConfig (A01) - this
 * controller only parses/validates request shape and delegates the ownership/state-
 * machine decisions (the actual security-relevant logic of this slice) to
 * BookingService, mirroring catalog's own controller/service split.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest request, JwtAuthenticationToken authentication) {
        BookingResponse response = service.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/bookings/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable String id, JwtAuthenticationToken authentication) {
        return service.get(parseId(id), authentication);
    }

    @GetMapping
    public List<BookingResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            JwtAuthenticationToken authentication) {
        BookingStatus parsedStatus = status != null ? parseStatus(status) : null;
        Instant parsedFrom = parseInstant(from, "from");
        Instant parsedTo = parseInstant(to, "to");
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw new InvalidBookingQueryException("'from' must not be after 'to'");
        }
        return service.list(parsedStatus, parsedFrom, parsedTo, authentication);
    }

    @PutMapping("/{id}/status")
    public BookingResponse updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateBookingStatusRequest request,
            JwtAuthenticationToken authentication) {
        UUID bookingId = parseId(id);
        BookingStatus targetStatus = parseStatus(request.status());
        return service.updateStatus(bookingId, targetStatus, authentication);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new MalformedBookingIdException(id);
        }
    }

    private static BookingStatus parseStatus(String status) {
        try {
            return BookingStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBookingStatusException(status);
        }
    }

    private static Instant parseInstant(String value, String paramName) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidBookingQueryException("'" + paramName + "' is not a valid ISO-8601 instant");
        }
    }
}
