package com.clinicscheduler.appointment.error;

public class DoubleBookingException extends RuntimeException {
    public DoubleBookingException() {
        super("That slot is no longer available");
    }
}
