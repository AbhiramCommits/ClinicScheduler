package com.clinicscheduler.provider.error;

public class SlotNotBookedException extends RuntimeException {
    public SlotNotBookedException(Long slotId) {
        super("Slot " + slotId + " is not currently booked");
    }
}
