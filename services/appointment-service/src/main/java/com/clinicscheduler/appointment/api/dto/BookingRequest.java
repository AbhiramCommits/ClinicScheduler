package com.clinicscheduler.appointment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record BookingRequest(
        @NotBlank @Size(max = 64) String patientId,
        @NotBlank @Size(max = 120) String patientName,
        @NotNull Long providerId,
        @NotNull Long slotId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime
) {
}
