package com.tether.incident.dto;

import com.tether.incident.model.Severity;
import com.tether.incident.model.Status;
import jakarta.validation.constraints.Size;

/** All fields optional; only non-null fields are applied. */
public record UpdateIncidentRequest(
        Status status,
        Severity severity,
        @Size(max = 100) String owner) {}
