package com.tether.incident.repo;

import com.tether.incident.model.Incident;
import com.tether.incident.model.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    List<Incident> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<Incident> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, Status status);
    Optional<Incident> findByIdAndTenantId(UUID id, String tenantId);
}
