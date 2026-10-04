package com.tether.incident.dto;

import com.tether.incident.model.Severity;
import com.tether.incident.model.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull Severity severity,
        Status status,          // optional, defaults to OPEN
        @Size(max = 100) String owner) {}
