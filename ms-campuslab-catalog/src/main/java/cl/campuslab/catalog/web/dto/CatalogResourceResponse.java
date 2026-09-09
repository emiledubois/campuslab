package cl.campuslab.catalog.web.dto;

import cl.campuslab.catalog.domain.CatalogResource;
import cl.campuslab.catalog.domain.ResourceType;
import java.time.Instant;
import java.util.UUID;

public record CatalogResourceResponse(
        UUID id,
        ResourceType resourceType,
        String name,
        String description,
        String location,
        Integer stock,
        Integer cupo,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static CatalogResourceResponse from(CatalogResource entity) {
        return new CatalogResourceResponse(
                entity.getId(),
                entity.getResourceType(),
                entity.getName(),
                entity.getDescription(),
                entity.getLocation(),
                entity.getStock(),
                entity.getCupo(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
