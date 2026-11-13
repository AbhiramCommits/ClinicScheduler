package com.clinicscheduler.provider.service;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.api.dto.PagedResponse;
import com.clinicscheduler.provider.domain.AvailabilitySlot;
import com.clinicscheduler.provider.domain.Provider;
import com.clinicscheduler.provider.domain.SlotStatus;
import com.clinicscheduler.provider.error.InvalidRequestException;
import com.clinicscheduler.provider.error.ResourceNotFoundException;
import com.clinicscheduler.provider.error.SlotAlreadyTakenException;
import com.clinicscheduler.provider.error.SlotNotBookedException;
import com.clinicscheduler.provider.repo.AvailabilitySlotRepository;
import com.clinicscheduler.provider.repo.ProviderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AvailabilityService {

    private final AvailabilitySlotRepository slotRepository;
    private final ProviderRepository providerRepository;

    public AvailabilityService(AvailabilitySlotRepository slotRepository, ProviderRepository providerRepository) {
        this.slotRepository = slotRepository;
        this.providerRepository = providerRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<AvailabilitySlotResponse> search(String specialty, String location,
                                                          LocalDateTime from, LocalDateTime to,
                                                          int page, int size) {
        if (!from.isBefore(to)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<AvailabilitySlotResponse> result = slotRepository.searchOpenSlots(
                blankToNull(specialty), blankToNull(location), from, to, pageable);
        return new PagedResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Optimistic locking contract: the read snapshot carries the row version, and the UPDATE
     * carries {@code where version = ?}. Two concurrent reserves can both read OPEN, but only one
     * UPDATE matches; the loser's flush throws an optimistic-lock exception which the API maps to
     * 409 SLOT_ALREADY_TAKEN.
     */
    @Transactional
    public AvailabilitySlotResponse reserve(Long slotId) {
        AvailabilitySlot slot = findSlot(slotId);
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new SlotAlreadyTakenException(slotId);
        }
        slot.setStatus(SlotStatus.BOOKED);
        AvailabilitySlot saved = slotRepository.saveAndFlush(slot);
        return toResponse(saved);
    }

    @Transactional
    public AvailabilitySlotResponse release(Long slotId) {
        AvailabilitySlot slot = findSlot(slotId);
        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new SlotNotBookedException(slotId);
        }
        slot.setStatus(SlotStatus.OPEN);
        AvailabilitySlot saved = slotRepository.saveAndFlush(slot);
        return toResponse(saved);
    }

    private AvailabilitySlot findSlot(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability slot " + slotId + " not found"));
    }

    private AvailabilitySlotResponse toResponse(AvailabilitySlot slot) {
        Provider provider = providerRepository.findById(slot.getProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Provider " + slot.getProviderId() + " not found"));
        return new AvailabilitySlotResponse(
                slot.getId(),
                provider.getId(),
                provider.getFullName(),
                provider.getSpecialty(),
                provider.getClinicLocation(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getStatus());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
