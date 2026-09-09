package cl.campuslab.catalog.service;

import cl.campuslab.catalog.domain.CatalogResource;
import cl.campuslab.catalog.domain.CatalogResourceRepository;
import cl.campuslab.catalog.domain.ResourceType;
import cl.campuslab.catalog.web.dto.CatalogResourceResponse;
import cl.campuslab.catalog.web.dto.CreateCatalogResourceRequest;
import cl.campuslab.catalog.web.dto.UpdateCatalogResourceRequest;
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

    private final CatalogResourceRepository repository;

    public CatalogResourceService(CatalogResourceRepository repository) {
        this.repository = repository;
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

        log.info("Catalog resource action=[CREATE] sub=[{}] roles=[{}] id=[{}]",
                subjectOf(authentication), rolesOf(authentication), saved.getId());
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

        log.info("Catalog resource action=[UPDATE] sub=[{}] roles=[{}] id=[{}] stockBefore=[{}] stockAfter=[{}] cupoBefore=[{}] cupoAfter=[{}]",
                subjectOf(authentication), rolesOf(authentication), saved.getId(),
                stockBefore, saved.getStock(), cupoBefore, saved.getCupo());
        return CatalogResourceResponse.from(saved);
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

    private static String subjectOf(JwtAuthenticationToken authentication) {
        return authentication.getToken().getSubject();
    }

    private static List<String> rolesOf(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
