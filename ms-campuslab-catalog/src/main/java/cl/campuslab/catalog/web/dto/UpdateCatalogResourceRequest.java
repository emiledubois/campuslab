package cl.campuslab.catalog.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * No {@code resourceType} field - it is immutable after creation (see design doc §3), so
 * a PUT can never change it, and the response always reflects the value set at creation.
 */
public record UpdateCatalogResourceRequest(
        @NotBlank(message = "name is required") @Size(max = 150, message = "name must be at most 150 characters") String name,
        @Size(max = 1000, message = "description must be at most 1000 characters") String description,
        @Size(max = 150, message = "location must be at most 150 characters") String location,
        @Min(value = 0, message = "stock must be >= 0") Integer stock,
        @Min(value = 0, message = "cupo must be >= 0") Integer cupo,
        @NotNull(message = "version is required") Long version) {
}
