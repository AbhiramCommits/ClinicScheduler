package com.clinicscheduler.provider.service;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.api.dto.PagedResponse;
import com.clinicscheduler.provider.api.dto.ProviderResponse;
import com.clinicscheduler.provider.domain.AvailabilitySlot;
import com.clinicscheduler.provider.domain.Provider;
import com.clinicscheduler.provider.domain.SlotStatus;
import com.clinicscheduler.provider.error.InvalidRequestException;
import com.clinicscheduler.provider.error.ResourceNotFoundException;
import com.clinicscheduler.provider.repo.AvailabilitySlotRepository;
import com.clinicscheduler.provider.repo.ProviderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProviderService {

    private final ProviderRepository providerRepository;
    private final AvailabilitySlotRepository slotRepository;

    public ProviderService(ProviderRepository providerRepository, AvailabilitySlotRepository slotRepository) {
        this.providerRepository = providerRepository;
        this.slotRepository = slotRepository;
    }

    public PagedResponse<ProviderResponse> listProviders(String specialty, String location, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "fullName"));
        Page<Provider> providers = providerRepository.search(blankToNull(specialty), blankToNull(location), pageable);
        return new PagedResponse<>(
                providers.getContent().stream().map(this::toResponse).toList(),
                providers.getNumber(),
                providers.getSize(),
                providers.getTotalElements(),
                providers.getTotalPages());
    }

    public ProviderResponse getProvider(Long id) {
        return toResponse(findProvider(id));
    }

    public List<AvailabilitySlotResponse> getAvailability(Long providerId, LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        Provider provider = findProvider(providerId);
        List<AvailabilitySlot> slots = slotRepository
                .findByProviderIdAndStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        providerId, SlotStatus.OPEN, from, to);
        return slots.stream().map(slot -> toResponse(slot, provider)).toList();
    }

    public Provider findProvider(Long id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Provider " + id + " not found"));
    }

    private ProviderResponse toResponse(Provider provider) {
        return new ProviderResponse(
                provider.getId(),
                provider.getFullName(),
                provider.getSpecialty(),
                provider.getNpiNumber(),
                provider.getClinicLocation(),
                provider.isActive(),
                provider.getCreatedAt());
    }

    private AvailabilitySlotResponse toResponse(AvailabilitySlot slot, Provider provider) {
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
