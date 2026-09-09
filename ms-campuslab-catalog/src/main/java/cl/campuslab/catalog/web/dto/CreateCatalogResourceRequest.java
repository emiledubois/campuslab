package cl.campuslab.catalog.web.dto;

import cl.campuslab.catalog.domain.ResourceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Bound onto by Jackson only, never onto the JPA entity directly (OWASP A08) - {@code id},
 * {@code version}, {@code createdAt}, {@code updatedAt} have no place in this DTO at all,
 * so nothing a caller sends for them can ever reach the entity.
 */
public record CreateCatalogResourceRequest(
        @NotNull(message = "resourceType is required") ResourceType resourceType,
        @NotBlank(message = "name is required") @Size(max = 150, message = "name must be at most 150 characters") String name,
        @Size(max = 1000, message = "description must be at most 1000 characters") String description,
        @Size(max = 150, message = "location must be at most 150 characters") String location,
        @Min(value = 0, message = "stock must be >= 0") Integer stock,
        @Min(value = 0, message = "cupo must be >= 0") Integer cupo) {
}
