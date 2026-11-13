package com.clinicscheduler.provider.security;

/**
 * Minimal principal reconstructed from the JWT. provider-service only needs to know that the
 * caller is authenticated; ownership rules live in appointment-service.
 */
public record AuthUser(String email, String name, String role, String patientId) {
}
