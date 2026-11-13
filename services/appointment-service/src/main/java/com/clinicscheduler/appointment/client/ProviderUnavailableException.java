package com.clinicscheduler.appointment.client;

public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
