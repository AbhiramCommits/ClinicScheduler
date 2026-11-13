package com.clinicscheduler.appointment.api.dto;

import com.clinicscheduler.appointment.domain.AppointmentStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record AppointmentResponse(
        Long id,
        String patientId,
        String patientName,
        Long providerId,
        Long slotId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        AppointmentStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
