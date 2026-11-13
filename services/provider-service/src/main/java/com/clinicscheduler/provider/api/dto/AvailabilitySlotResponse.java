package com.clinicscheduler.provider.api.dto;

import com.clinicscheduler.provider.domain.SlotStatus;

import java.time.LocalDateTime;

public record AvailabilitySlotResponse(
        Long id,
        Long providerId,
        String providerName,
        String specialty,
        String clinicLocation,
        LocalDateTime startTime,
        LocalDateTime endTime,
        SlotStatus status
) {
}
