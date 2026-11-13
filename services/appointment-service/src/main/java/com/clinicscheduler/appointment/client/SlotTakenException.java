package com.clinicscheduler.appointment.client;

public class SlotTakenException extends RuntimeException {
    public SlotTakenException(Long slotId) {
        super("Slot " + slotId + " was already reserved");
    }
}
