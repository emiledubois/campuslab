package cl.campuslab.audit.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID>, JpaSpecificationExecutor<TimelineEvent> {

    boolean existsByEventId(String eventId);
}
