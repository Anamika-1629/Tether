package com.tether.auth.repo;

import com.tether.auth.model.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByJoinCode(String joinCode);
    boolean existsBySlug(String slug);
    boolean existsByJoinCode(String joinCode);
}
