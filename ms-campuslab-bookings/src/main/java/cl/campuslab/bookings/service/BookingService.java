package cl.campuslab.bookings.service;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingRepository;
import cl.campuslab.bookings.domain.BookingSpecifications;
import cl.campuslab.bookings.domain.BookingStatus;
import cl.campuslab.bookings.web.dto.BookingResponse;
import cl.campuslab.bookings.web.dto.CreateBookingRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * State-machine legality is a small, static, in-code lookup (design doc §6) - not a
 * GoF State pattern, not a new Strategy abstraction. {@code STAFF_TRANSITIONS} encodes
 * the base transition table for TECNICO/ADMIN; ESTUDIANTE's narrower cancellation
 * window is handled separately since it's a role-specific carve-out, not a symmetric
 * subset of the base graph (an ESTUDIANTE may never reach any target other than
 * CANCELADA at all, enforced earlier by the role-target gate).
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final Map<BookingStatus, Set<BookingStatus>> STAFF_TRANSITIONS = Map.of(
            BookingStatus.SOLICITADA, Set.of(BookingStatus.APROBADA, BookingStatus.CANCELADA),
            BookingStatus.APROBADA, Set.of(BookingStatus.EN_PREPARACION, BookingStatus.CANCELADA),
            BookingStatus.EN_PREPARACION, Set.of(BookingStatus.EN_USO, BookingStatus.CANCELADA),
            BookingStatus.EN_USO, Set.of(BookingStatus.DEVUELTA),
            BookingStatus.DEVUELTA, Set.of(),
            BookingStatus.CANCELADA, Set.of());

    private static final Set<BookingStatus> ESTUDIANTE_CANCEL_FROM =
            Set.of(BookingStatus.SOLICITADA, BookingStatus.APROBADA);

    private final BookingRepository repository;

    public BookingService(BookingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest request, JwtAuthenticationToken authentication) {
        if (!request.requestedEnd().isAfter(request.requestedStart())) {
            throw new InvalidBookingWindowException("requestedEnd must be strictly after requestedStart");
        }

        String studentSub = subjectOf(authentication);
        Booking entity = new Booking(
                request.resourceId(), studentSub, request.requestedStart(), request.requestedEnd(), request.notes());
        Booking saved = repository.save(entity);

        log.info("Booking action=[CREATE] sub=[{}] roles=[{}] id=[{}]", studentSub, rolesOf(authentication), saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID id, JwtAuthenticationToken authentication) {
        Booking booking = repository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));

        if (isEstudiante(authentication) && !booking.getStudentSub().equals(subjectOf(authentication))) {
            throw new BookingNotFoundException(id);
        }

        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list(BookingStatus status, Instant from, Instant to, JwtAuthenticationToken authentication) {
        Specification<Booking> spec = Specification.where(null);

        if (isEstudiante(authentication)) {
            spec = spec.and(BookingSpecifications.hasStudentSub(subjectOf(authentication)));
        }
        if (status != null) {
            spec = spec.and(BookingSpecifications.hasStatus(status));
        }
        if (from != null) {
            spec = spec.and(BookingSpecifications.requestedStartAfterOrEqual(from));
        }
        if (to != null) {
            spec = spec.and(BookingSpecifications.requestedStartBeforeOrEqual(to));
        }

        return repository.findAll(spec).stream().map(BookingResponse::from).toList();
    }

    @Transactional
    public BookingResponse updateStatus(UUID id, BookingStatus targetStatus, JwtAuthenticationToken authentication) {
        Booking booking = repository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
        boolean isEstudiante = isEstudiante(authentication);
        String sub = subjectOf(authentication);

        if (isEstudiante && !booking.getStudentSub().equals(sub)) {
            throw new BookingNotFoundException(id);
        }
        if (isEstudiante && targetStatus != BookingStatus.CANCELADA) {
            throw new BookingTransitionNotPermittedException(targetStatus);
        }
        if (!isLegalTransition(booking.getStatus(), targetStatus, isEstudiante)) {
            throw new IllegalBookingTransitionException(booking.getStatus(), targetStatus);
        }

        BookingStatus fromStatus = booking.getStatus();
        booking.transitionTo(targetStatus);
        // saveAndFlush forces Hibernate's own version-column check (WHERE id=? AND version=?)
        // to run now, inside this method - the real defense against two concurrent PUTs
        // both having read the same version and racing to update it (see design doc §3/AC19).
        Booking saved = repository.saveAndFlush(booking);

        log.info("Booking action=[STATUS_CHANGE] sub=[{}] roles=[{}] id=[{}] fromStatus=[{}] toStatus=[{}]",
                sub, rolesOf(authentication), saved.getId(), fromStatus, saved.getStatus());
        return BookingResponse.from(saved);
    }

    private static boolean isLegalTransition(BookingStatus current, BookingStatus target, boolean isEstudiante) {
        if (isEstudiante) {
            return ESTUDIANTE_CANCEL_FROM.contains(current);
        }
        return STAFF_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }

    private static boolean isEstudiante(JwtAuthenticationToken authentication) {
        return hasRole(authentication, "ESTUDIANTE");
    }

    private static boolean hasRole(JwtAuthenticationToken authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    private static String subjectOf(JwtAuthenticationToken authentication) {
        return authentication.getToken().getSubject();
    }

    private static List<String> rolesOf(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
