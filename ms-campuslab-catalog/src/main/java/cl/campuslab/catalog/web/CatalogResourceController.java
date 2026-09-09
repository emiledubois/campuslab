package cl.campuslab.catalog.web;

import cl.campuslab.catalog.service.CatalogResourceService;
import cl.campuslab.catalog.service.MalformedResourceIdException;
import cl.campuslab.catalog.web.dto.CatalogResourceResponse;
import cl.campuslab.catalog.web.dto.CreateCatalogResourceRequest;
import cl.campuslab.catalog.web.dto.UpdateCatalogResourceRequest;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Role enforcement itself lives in path rules in SecurityConfig (A01) - this controller
 * only builds responses once a caller has already passed that gate, mirroring the BFF's
 * own AdminPingController/MeController split.
 */
@RestController
@RequestMapping("/api/catalog/resources")
public class CatalogResourceController {

    private final CatalogResourceService service;

    public CatalogResourceController(CatalogResourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<CatalogResourceResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<CatalogResourceResponse> create(
            @Valid @RequestBody CreateCatalogResourceRequest request, JwtAuthenticationToken authentication) {
        CatalogResourceResponse response = service.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/catalog/resources/" + response.id())).body(response);
    }

    @PutMapping("/{id}")
    public CatalogResourceResponse update(
            @PathVariable String id, @Valid @RequestBody UpdateCatalogResourceRequest request,
            JwtAuthenticationToken authentication) {
        return service.update(parseId(id), request, authentication);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new MalformedResourceIdException(id);
        }
    }
}
