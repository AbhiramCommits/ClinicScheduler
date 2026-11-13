package com.clinicscheduler.provider.api;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.api.dto.PagedResponse;
import com.clinicscheduler.provider.api.dto.ProviderResponse;
import com.clinicscheduler.provider.service.AvailabilityService;
import com.clinicscheduler.provider.service.ProviderService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class ProviderController {

    private final ProviderService providerService;
    private final AvailabilityService availabilityService;

    public ProviderController(ProviderService providerService, AvailabilityService availabilityService) {
        this.providerService = providerService;
        this.availabilityService = availabilityService;
    }

    @GetMapping("/providers")
    public PagedResponse<ProviderResponse> listProviders(
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return providerService.listProviders(specialty, location, page, size);
    }

    @GetMapping("/providers/{id}")
    public ProviderResponse getProvider(@PathVariable Long id) {
        return providerService.getProvider(id);
    }

    @GetMapping("/providers/{id}/availability")
    public List<AvailabilitySlotResponse> getProviderAvailability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return providerService.getAvailability(id, from, to);
    }

    @GetMapping("/availability/search")
    public PagedResponse<AvailabilitySlotResponse> searchAvailability(
            @RequestParam(required = false) String specialty,
            @RequestParam(required = false) String location,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return availabilityService.search(specialty, location, from, to, page, size);
    }

    @PostMapping("/availability/{slotId}/reserve")
    public AvailabilitySlotResponse reserve(@PathVariable Long slotId) {
        return availabilityService.reserve(slotId);
    }

    @PostMapping("/availability/{slotId}/release")
    public AvailabilitySlotResponse release(@PathVariable Long slotId) {
        return availabilityService.release(slotId);
    }
}
