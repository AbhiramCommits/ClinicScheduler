package com.clinicscheduler.appointment.api.dto;

public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(String email, String name, String role, String patientId) {
    }
}
