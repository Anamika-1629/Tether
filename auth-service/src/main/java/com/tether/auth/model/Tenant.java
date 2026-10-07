package com.tether.auth.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    /** Shared by the owner so teammates can register into this tenant. */
    @Column(name = "join_code", nullable = false)
    private String joinCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(String name, String slug, String joinCode) {
        this.name = name;
        this.slug = slug;
        this.joinCode = joinCode;
    }

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getJoinCode() { return joinCode; }
    public Instant getCreatedAt() { return createdAt; }
}
