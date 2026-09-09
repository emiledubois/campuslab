package cl.campuslab.catalog.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogResourceRepository extends JpaRepository<CatalogResource, UUID> {
}
