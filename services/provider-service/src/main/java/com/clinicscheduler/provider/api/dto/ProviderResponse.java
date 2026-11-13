package com.clinicscheduler.provider.api.dto;

import java.time.Instant;

public record ProviderResponse(
        Long id,
        String fullName,
        String specialty,
        String npiNumber,
        String clinicLocation,
        boolean active,
        Instant createdAt
) {
}
