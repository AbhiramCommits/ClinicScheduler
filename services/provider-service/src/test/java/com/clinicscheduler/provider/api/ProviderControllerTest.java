package com.clinicscheduler.provider.api;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.api.dto.PagedResponse;
import com.clinicscheduler.provider.api.dto.ProviderResponse;
import com.clinicscheduler.provider.config.SecurityConfig;
import com.clinicscheduler.provider.domain.SlotStatus;
import com.clinicscheduler.provider.error.InvalidRequestException;
import com.clinicscheduler.provider.error.ResourceNotFoundException;
import com.clinicscheduler.provider.error.SlotAlreadyTakenException;
import com.clinicscheduler.provider.error.SlotNotBookedException;
import com.clinicscheduler.provider.security.JwtAuthFilter;
import com.clinicscheduler.provider.security.JwtService;
import com.clinicscheduler.provider.service.AvailabilityService;
import com.clinicscheduler.provider.service.ProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProviderController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class})
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long!!")
class ProviderControllerTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 5, 1, 9, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderService providerService;

    @MockBean
    private AvailabilityService availabilityService;

    private final ProviderResponse provider =
            new ProviderResponse(1L, "Dr. Test", "Cardiology", "1234567890", "Downtown Clinic",
                    true, Instant.parse("2030-01-01T00:00:00Z"));

    private final AvailabilitySlotResponse slot =
            new AvailabilitySlotResponse(10L, 1L, "Dr. Test", "Cardiology", "Downtown Clinic",
                    START, START.plusMinutes(30), SlotStatus.OPEN);

    @Test
    void listsProvidersWithPagination() throws Exception {
        when(providerService.listProviders(null, null, 0, 20))
                .thenReturn(new PagedResponse<>(List.of(provider), 0, 20, 1, 1));

        mockMvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Dr. Test"));
    }

    @Test
    void rejectsAnInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/providers").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void missingProviderIs404() throws Exception {
        when(providerService.getProvider(9L)).thenThrow(new ResourceNotFoundException("Provider 9 not found"));

        mockMvc.perform(get("/api/providers/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void providerAvailabilityRequiresBothDates() throws Exception {
        mockMvc.perform(get("/api/providers/1/availability").param("from", "2030-05-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void providerAvailabilityRejectsUnparseableDate() throws Exception {
        mockMvc.perform(get("/api/providers/1/availability")
                        .param("from", "not-a-date")
                        .param("to", "2030-05-02T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void searchRequiresFromAndTo() throws Exception {
        mockMvc.perform(get("/api/availability/search").param("to", "2030-05-02T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void searchRejectsInvertedRange() throws Exception {
        when(availabilityService.search(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new InvalidRequestException("'from' must be before 'to'"));

        mockMvc.perform(get("/api/availability/search")
                        .param("from", "2030-05-02T00:00:00")
                        .param("to", "2030-05-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void searchReturnsOpenSlots() throws Exception {
        when(availabilityService.search(eq("Cardiology"), eq("Downtown Clinic"), any(), any(), eq(0), eq(20)))
                .thenReturn(new PagedResponse<>(List.of(slot), 0, 20, 1, 1));

        mockMvc.perform(get("/api/availability/search")
                        .param("specialty", "Cardiology")
                        .param("location", "Downtown Clinic")
                        .param("from", "2030-05-01T00:00:00")
                        .param("to", "2030-05-02T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10));
    }

    @Test
    void reserveWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/availability/10/reserve"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void reserveReturnsBookedSlot() throws Exception {
        AvailabilitySlotResponse booked =
                new AvailabilitySlotResponse(10L, 1L, "Dr. Test", "Cardiology", "Downtown Clinic",
                        START, START.plusMinutes(30), SlotStatus.BOOKED);
        when(availabilityService.reserve(10L)).thenReturn(booked);

        mockMvc.perform(post("/api/availability/10/reserve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void reserveConflictIs409() throws Exception {
        when(availabilityService.reserve(10L)).thenThrow(new SlotAlreadyTakenException(10L));

        mockMvc.perform(post("/api/availability/10/reserve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_ALREADY_TAKEN"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void releaseOfAnOpenSlotIs409() throws Exception {
        when(availabilityService.release(10L)).thenThrow(new SlotNotBookedException(10L));

        mockMvc.perform(post("/api/availability/10/release"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_NOT_BOOKED"));
    }
}
