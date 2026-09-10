package cl.campuslab.catalog.service;

import cl.campuslab.catalog.domain.CatalogResource;
import cl.campuslab.catalog.domain.CatalogResourceRepository;
import cl.campuslab.catalog.domain.ResourceType;
import cl.campuslab.catalog.domain.StockDecrementLedger;
import cl.campuslab.catalog.domain.StockDecrementLedgerKey;
import cl.campuslab.catalog.domain.StockDecrementLedgerRepository;
import cl.campuslab.catalog.web.dto.CatalogResourceResponse;
import cl.campuslab.catalog.web.dto.CreateCatalogResourceRequest;
import cl.campuslab.catalog.web.dto.UpdateCatalogResourceRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogResourceService {

    private static final Logger log = LoggerFactory.getLogger(CatalogResourceService.class);

    /** Increment's internal retry budget on a lost optimistic-lock race (design doc §2.2/§4). */
    private static final int MAX_INCREMENT_ATTEMPTS = 3;

    private final CatalogResourceRepository repository;
    private final StockDecrementLedgerRepository ledgerRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public CatalogResourceService(CatalogResourceRepository repository, StockDecrementLedgerRepository ledgerRepository) {
        this.repository = repository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(readOnly = true)
    public List<CatalogResourceResponse> list() {
        return repository.findAll().stream().map(CatalogResourceResponse::from).toList();
    }

    @Transactional
    public CatalogResourceResponse create(CreateCatalogResourceRequest request, JwtAuthenticationToken authentication) {
        validateShape(request.resourceType(), request.stock(), request.cupo());

        CatalogResource entity = new CatalogResource(
                request.resourceType(), request.name(), request.description(), request.location(),
                request.stock(), request.cupo());
        CatalogResource saved = repository.save(entity);

        log.info("Catalog resource action=[CREATE] oid=[{}] roles=[{}] id=[{}]",
                oidOf(authentication), rolesOf(authentication), saved.getId());
        return CatalogResourceResponse.from(saved);
    }

    @Transactional
    public CatalogResourceResponse update(UUID id, UpdateCatalogResourceRequest request, JwtAuthenticationToken authentication) {
        CatalogResource entity = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));

        validateShape(entity.getResourceType(), request.stock(), request.cupo());

        if (!Objects.equals(entity.getVersion(), request.version())) {
            throw new ObjectOptimisticLockingFailureException(CatalogResource.class, id);
        }

        Integer stockBefore = entity.getStock();
        Integer cupoBefore = entity.getCupo();
        entity.applyUpdate(request.name(), request.description(), request.location(), request.stock(), request.cupo());
        // saveAndFlush forces Hibernate's own version-column check (WHERE id=? AND version=?)
        // to run now, inside this method - the real defense against two concurrent PUTs
        // both having read version=0 and racing to update it (see design doc §4/AC15).
        CatalogResource saved = repository.saveAndFlush(entity);

        log.info("Catalog resource action=[UPDATE] oid=[{}] roles=[{}] id=[{}] stockBefore=[{}] stockAfter=[{}] cupoBefore=[{}] cupoAfter=[{}]",
                oidOf(authentication), rolesOf(authentication), saved.getId(),
                stockBefore, saved.getStock(), cupoBefore, saved.getCupo());
        return CatalogResourceResponse.from(saved);
    }

    /**
     * The approval saga's decrement step (design doc §4) - {@code bookingId} is the
     * idempotency key: a replayed call for the same {@code (resourceId, bookingId)} pair
     * (e.g. bookings retrying after treating a slow response as a timeout, AC6) returns
     * the current state unchanged instead of decrementing a second time. One transaction:
     * the ledger check, the stock read, the {@code @Version}-checked write and the ledger
     * insert all commit or roll back together.
     */
    @Transactional
    public CatalogResourceResponse decrement(UUID resourceId, UUID bookingId, JwtAuthenticationToken authentication) {
        StockDecrementLedgerKey key = new StockDecrementLedgerKey(resourceId, bookingId);
        if (ledgerRepository.existsById(key)) {
            CatalogResource existing = repository.findById(resourceId).orElseThrow(() -> new ResourceNotFoundException(resourceId));
            return CatalogResourceResponse.from(existing);
        }

        CatalogResource resource = repository.findById(resourceId).orElseThrow(() -> new ResourceNotFoundException(resourceId));
        if (resource.currentCount() <= 0) {
            throw new InsufficientStockException();
        }

        int countBefore = resource.currentCount();
        resource.decrementCount();
        // saveAndFlush forces Hibernate's own version-column check to run now, inside this
        // transaction - the real defense against two concurrent decrements on the last unit
        // of stock (design doc §4/§7 A04/AC3).
        CatalogResource saved = repository.saveAndFlush(resource);
        ledgerRepository.save(new StockDecrementLedger(resourceId, bookingId));

        log.info("Catalog resource action=[DECREMENT] oid=[{}] roles=[{}] resourceId=[{}] bookingId=[{}] countBefore=[{}] countAfter=[{}]",
                oidOf(authentication), rolesOf(authentication), resourceId, bookingId, countBefore, saved.currentCount());
        return CatalogResourceResponse.from(saved);
    }

    /**
     * The approval saga's compensating step (design doc §4) - restores the unit given
     * back by {@link #decrement} when bookings' own local commit fails after the decrement
     * already succeeded. Unlike decrement, this must not fail cleanly under contention: it
     * retries internally, up to {@link #MAX_INCREMENT_ATTEMPTS} times, on a lost
     * optimistic-lock race, re-reading the resource fresh from the database each time (the
     * {@code entityManager.clear()} evicts the stale, already-conflicted managed instance so
     * the retry's {@code findById} genuinely re-queries instead of returning the same
     * detached copy from the first-level cache).
     */
    @Transactional
    public CatalogResourceResponse increment(UUID resourceId, UUID bookingId, JwtAuthenticationToken authentication) {
        StockDecrementLedgerKey key = new StockDecrementLedgerKey(resourceId, bookingId);
        if (!ledgerRepository.existsById(key)) {
            CatalogResource existing = repository.findById(resourceId).orElseThrow(() -> new ResourceNotFoundException(resourceId));
            return CatalogResourceResponse.from(existing);
        }

        for (int attempt = 1; attempt <= MAX_INCREMENT_ATTEMPTS; attempt++) {
            CatalogResource resource = repository.findById(resourceId).orElseThrow(() -> {
                log.error("Catalog resource action=[INCREMENT_FAILED] resourceId=[{}] bookingId=[{}] reason=[NOT_FOUND]",
                        resourceId, bookingId);
                return new ResourceNotFoundException(resourceId);
            });
            int countBefore = resource.currentCount();
            resource.incrementCount();
            try {
                CatalogResource saved = repository.saveAndFlush(resource);
                ledgerRepository.deleteById(key);
                log.info("Catalog resource action=[INCREMENT] oid=[{}] roles=[{}] resourceId=[{}] bookingId=[{}] countBefore=[{}] countAfter=[{}]",
                        oidOf(authentication), rolesOf(authentication), resourceId, bookingId, countBefore, saved.currentCount());
                return CatalogResourceResponse.from(saved);
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt == MAX_INCREMENT_ATTEMPTS) {
                    throw ex;
                }
                entityManager.clear();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static void validateShape(ResourceType resourceType, Integer stock, Integer cupo) {
        if (resourceType == ResourceType.LABORATORIO) {
            if (cupo == null) {
                throw new InvalidResourceShapeException("cupo is required for resourceType LABORATORIO");
            }
            if (stock != null) {
                throw new InvalidResourceShapeException("stock must be absent for resourceType LABORATORIO");
            }
        } else {
            if (stock == null) {
                throw new InvalidResourceShapeException("stock is required for resourceType " + resourceType);
            }
            if (cupo != null) {
                throw new InvalidResourceShapeException("cupo must be absent for resourceType " + resourceType);
            }
        }
    }

    /** Display/logging only (cross-service correlation, see docs/designs/entra-migration.md
     * §3/§7 A09) - falls back to {@code sub} defensively since catalog resources have no
     * ownership concept for this value to gate. */
    private static String oidOf(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : authentication.getToken().getSubject();
    }

    private static List<String> rolesOf(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
