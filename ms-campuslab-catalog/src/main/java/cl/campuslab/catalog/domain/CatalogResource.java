package cl.campuslab.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code id} is application-assigned (Hibernate's built-in UUID generator) rather than
 * a DB default - avoids depending on pgcrypto/gen_random_uuid() and avoids sequential,
 * enumerable IDs (IDOR mitigation, OWASP A01/A04). {@code resourceType} is immutable
 * after creation (no setter): the PUT contract never lets a caller change it.
 */
@Entity
@Table(name = "catalog_resource")
public class CatalogResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20, updatable = false)
    private ResourceType resourceType;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 150)
    private String location;

    private Integer stock;

    private Integer cupo;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogResource() {
        // JPA
    }

    public CatalogResource(ResourceType resourceType, String name, String description, String location,
                            Integer stock, Integer cupo) {
        this.resourceType = resourceType;
        this.name = name;
        this.description = description;
        this.location = location;
        this.stock = stock;
        this.cupo = cupo;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void applyUpdate(String name, String description, String location, Integer stock, Integer cupo) {
        this.name = name;
        this.description = description;
        this.location = location;
        this.stock = stock;
        this.cupo = cupo;
    }

    public UUID getId() {
        return id;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getLocation() {
        return location;
    }

    public Integer getStock() {
        return stock;
    }

    public Integer getCupo() {
        return cupo;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
