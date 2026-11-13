package com.clinicscheduler.provider.error;

public class SlotAlreadyTakenException extends RuntimeException {
    public SlotAlreadyTakenException(Long slotId) {
        super("Slot " + slotId + " is no longer open");
    }
}
