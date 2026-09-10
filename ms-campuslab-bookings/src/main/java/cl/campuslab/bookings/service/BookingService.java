package cl.campuslab.bookings.service;

import cl.campuslab.bookings.catalog.CatalogDecrementOutcome;
import cl.campuslab.bookings.catalog.CatalogStockClient;
import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingRepository;
import cl.campuslab.bookings.domain.BookingSpecifications;
import cl.campuslab.bookings.domain.BookingStatus;
import cl.campuslab.bookings.messaging.BookingEventPublisher;
import cl.campuslab.bookings.messaging.NotificationType;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private final CatalogStockClient catalogStockClient;
    private final BookingEventPublisher eventPublisher;

    public BookingService(
            BookingRepository repository, CatalogStockClient catalogStockClient, BookingEventPublisher eventPublisher) {
        this.repository = repository;
        this.catalogStockClient = catalogStockClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest request, JwtAuthenticationToken authentication) {
        if (!request.requestedEnd().isAfter(request.requestedStart())) {
            throw new InvalidBookingWindowException("requestedEnd must be strictly after requestedStart");
        }

        String studentOid = ownerOidOf(authentication);
        Booking entity = new Booking(
                request.resourceId(), studentOid, request.requestedStart(), request.requestedEnd(), request.notes());
        Booking saved = repository.save(entity);

        log.info("Booking action=[CREATE] oid=[{}] roles=[{}] id=[{}]", studentOid, rolesOf(authentication), saved.getId());
        return BookingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID id, JwtAuthenticationToken authentication) {
        Booking booking = repository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));

        if (isEstudiante(authentication) && !booking.getStudentOid().equals(ownerOidOf(authentication))) {
            throw new BookingNotFoundException(id);
        }

        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list(BookingStatus status, Instant from, Instant to, JwtAuthenticationToken authentication) {
        Specification<Booking> spec = Specification.where(null);

        if (isEstudiante(authentication)) {
            spec = spec.and(BookingSpecifications.hasStudentOid(ownerOidOf(authentication)));
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

    /**
     * Deliberately NOT {@code @Transactional} at this level (design doc §4's transactional-
     * boundary note): the {@code SOLICITADA -> APROBADA} branch makes an outbound HTTP call
     * to catalog between the load and the write, and must never hold a Postgres connection
     * open across that network round-trip (pool exhaustion risk under load). The load
     * ({@code repository.findById}) and the write ({@code repository.saveAndFlush}, in both
     * this method and {@link #approveWithCatalogSaga}) are each already atomic on their own -
     * every {@code SimpleJpaRepository} method runs in its own short transaction by default -
     * so no step here needs an enclosing transaction, and no self-invocation/proxy pitfall
     * arises from that (nothing relies on an enclosing {@code @Transactional} on this class).
     */
    public BookingResponse updateStatus(UUID id, BookingStatus targetStatus, JwtAuthenticationToken authentication) {
        Booking booking = repository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
        boolean isEstudiante = isEstudiante(authentication);

        if (isEstudiante && !booking.getStudentOid().equals(ownerOidOf(authentication))) {
            throw new BookingNotFoundException(id);
        }
        if (isEstudiante && targetStatus != BookingStatus.CANCELADA) {
            throw new BookingTransitionNotPermittedException(targetStatus);
        }
        if (!isLegalTransition(booking.getStatus(), targetStatus, isEstudiante)) {
            throw new IllegalBookingTransitionException(booking.getStatus(), targetStatus);
        }

        if (booking.getStatus() == BookingStatus.SOLICITADA && targetStatus == BookingStatus.APROBADA) {
            return approveWithCatalogSaga(booking, authentication);
        }

        BookingStatus fromStatus = booking.getStatus();
        booking.transitionTo(targetStatus);
        // saveAndFlush forces Hibernate's own version-column check (WHERE id=? AND version=?)
        // to run now - the real defense against two concurrent PUTs both having read the
        // same version and racing to update it (see design doc §3/AC19).
        Booking saved = repository.saveAndFlush(booking);

        log.info("Booking action=[STATUS_CHANGE] oid=[{}] roles=[{}] id=[{}] fromStatus=[{}] toStatus=[{}]",
                displayOidOf(authentication), rolesOf(authentication), saved.getId(), fromStatus, saved.getStatus());
        publishTransitionNotifications(fromStatus, saved.getStatus(), saved);
        return BookingResponse.from(saved);
    }

    /**
     * Publish hooks fire strictly AFTER the successful commit above, for exactly the two
     * transitions the case document names an email trigger for outside of approval
     * (messaging-notify.md §2.2's table) - {@code CANCELADA} and any transition not in
     * this table publish nothing.
     */
    private void publishTransitionNotifications(BookingStatus fromStatus, BookingStatus toStatus, Booking saved) {
        String traceId = UUID.randomUUID().toString();
        if (fromStatus == BookingStatus.EN_PREPARACION && toStatus == BookingStatus.EN_USO) {
            eventPublisher.publish(NotificationType.EMAIL_ROOM_READY, saved, fromStatus, toStatus, traceId);
        } else if (fromStatus == BookingStatus.EN_USO && toStatus == BookingStatus.DEVUELTA) {
            eventPublisher.publish(NotificationType.EMAIL_RETURNED, saved, fromStatus, toStatus, traceId);
        }
    }

    /**
     * The one saga edge in this service (design doc §2.1/§3): step 1 is catalog's atomic
     * decrement (outside any local transaction), step 2 is this booking's local commit.
     * Catalog's increment is the one compensating transaction, invoked only when step 2
     * fails after step 1 already succeeded.
     */
    private BookingResponse approveWithCatalogSaga(Booking booking, JwtAuthenticationToken authentication) {
        UUID bookingId = booking.getId();
        UUID resourceId = booking.getResourceId();
        String bearerToken = bearerTokenOf(authentication);

        CatalogDecrementOutcome outcome = catalogStockClient.decrement(resourceId, bookingId, bearerToken);
        log.info("Booking action=[APPROVE_SAGA] oid=[{}] roles=[{}] id=[{}] resourceId=[{}] catalogDecrementResult=[{}]",
                displayOidOf(authentication), rolesOf(authentication), bookingId, resourceId, outcome);

        switch (outcome) {
            case INSUFFICIENT_STOCK -> throw new CatalogInsufficientStockException();
            case NOT_FOUND -> throw new CatalogResourceNotFoundException();
            case UNREACHABLE, UNEXPECTED_ERROR -> throw new CatalogServiceUnavailableException();
            case SUCCESS -> { /* proceed to the local commit below */ }
        }

        try {
            booking.transitionTo(BookingStatus.APROBADA);
            Booking saved = repository.saveAndFlush(booking);
            log.info("Booking action=[STATUS_CHANGE] oid=[{}] roles=[{}] id=[{}] fromStatus=[{}] toStatus=[{}]",
                    displayOidOf(authentication), rolesOf(authentication), saved.getId(), BookingStatus.SOLICITADA, saved.getStatus());
            // messaging-notify.md §2.2's table: SOLICITADA->APROBADA fires both the
            // student-facing approval email and the tecnico-facing prep ticket, sharing one
            // traceId (only this edge ever publishes two messages for the same request).
            String traceId = UUID.randomUUID().toString();
            eventPublisher.publish(NotificationType.EMAIL_APPROVED, saved, BookingStatus.SOLICITADA, BookingStatus.APROBADA, traceId);
            eventPublisher.publish(NotificationType.PREP_TICKET_REQUESTED, saved, BookingStatus.SOLICITADA, BookingStatus.APROBADA, traceId);
            return BookingResponse.from(saved);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Revision 1 (design doc §3 step 2): the local write can lose for two different
            // reasons, and only one of them warrants compensating. Postgres's row lock on
            // whichever UPDATE won guarantees its commit is already visible under
            // read-committed isolation by the time this exact exception is thrown here, so
            // this plain, non-transactional re-read is safe - no staleness risk.
            BookingStatus currentStatus = repository.findById(bookingId)
                    .map(Booking::getStatus)
                    .orElse(null);
            if (currentStatus == BookingStatus.APROBADA) {
                log.info("Booking action=[APPROVE_SAGA] id=[{}] resourceId=[{}] bookingId=[{}] decision=[SKIP_COMPENSATION_DUPLICATE_APPROVAL]",
                        bookingId, resourceId, bookingId);
            } else {
                log.error("Booking action=[APPROVE_COMPENSATION_INVOKED] id=[{}] resourceId=[{}] bookingId=[{}] reason=[LOCAL_OPTIMISTIC_LOCK_LOST]",
                        bookingId, resourceId, bookingId);
                boolean compensated = catalogStockClient.increment(resourceId, bookingId, bearerToken);
                if (!compensated) {
                    log.error("Booking action=[APPROVE_COMPENSATION_FAILED] id=[{}] resourceId=[{}] bookingId=[{}] reason=[CATALOG_UNREACHABLE_DURING_COMPENSATION]",
                            bookingId, resourceId, bookingId);
                }
            }
            throw ex;
        }
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

    /**
     * Strict ownership-key extraction (Entra {@code oid}, not {@code sub}) - deliberately
     * has NO fallback to {@code sub} (unlike display-only fields elsewhere): a token
     * missing {@code oid} is treated as malformed/wrong-kind and rejected (401), never
     * silently degraded to a different, unstable ownership scope. See
     * docs/designs/entra-migration.md §4/§7 A01.
     */
    private static String ownerOidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        if (oid == null || oid.isBlank()) {
            throw new MissingOwnerOidException();
        }
        return oid;
    }

    /** Display/logging only (cross-service correlation) - unlike {@link #ownerOidOf},
     * this may fall back to {@code sub} since staff (TECNICO/ADMIN) callers have no
     * ownership dimension and a missing {@code oid} on their token must not block an
     * otherwise-legitimate status change. */
    private static String displayOidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : authentication.getToken().getSubject();
    }

    private static List<String> rolesOf(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * The original caller's own token, forwarded unchanged to catalog (design doc §2.3 - no
     * token minting, no service credential). Never logged anywhere, including here.
     */
    private static String bearerTokenOf(JwtAuthenticationToken authentication) {
        return "Bearer " + authentication.getToken().getTokenValue();
    }
}
