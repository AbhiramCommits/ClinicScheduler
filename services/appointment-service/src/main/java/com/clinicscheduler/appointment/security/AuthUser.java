package com.clinicscheduler.appointment.security;

/**
 * Principal placed in the SecurityContext after JWT validation. Keeping the role and the
 * patient id as claims avoids a user lookup on every request in this demo system.
 */
public record AuthUser(String email, String name, String role, String patientId) {

    public boolean isStaff() {
        return "STAFF".equals(role);
    }
}
