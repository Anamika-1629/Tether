package com.tether.auth.repo;

import com.tether.auth.model.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    @EntityGraph(attributePaths = "tenant")
    Optional<UserAccount> findByEmail(String email);

    @EntityGraph(attributePaths = "tenant")
    Optional<UserAccount> findByIdAndTenantId(UUID id, UUID tenantId);

    List<UserAccount> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    boolean existsByEmail(String email);
}
