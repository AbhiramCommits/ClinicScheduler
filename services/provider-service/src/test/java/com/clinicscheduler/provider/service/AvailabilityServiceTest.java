package com.clinicscheduler.provider.service;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.domain.AvailabilitySlot;
import com.clinicscheduler.provider.domain.Provider;
import com.clinicscheduler.provider.domain.SlotStatus;
import com.clinicscheduler.provider.error.InvalidRequestException;
import com.clinicscheduler.provider.error.ResourceNotFoundException;
import com.clinicscheduler.provider.error.SlotAlreadyTakenException;
import com.clinicscheduler.provider.error.SlotNotBookedException;
import com.clinicscheduler.provider.repo.AvailabilitySlotRepository;
import com.clinicscheduler.provider.repo.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 5, 1, 9, 0);

    @Mock
    private AvailabilitySlotRepository slotRepository;

    @Mock
    private ProviderRepository providerRepository;

    @InjectMocks
    private AvailabilityService service;

    private final Provider provider = new Provider("Dr. Test", "Cardiology", "1234567890", "Downtown Clinic", true);

    @Test
    void reserveFlipsOpenSlotToBooked() {
        AvailabilitySlot slot = new AvailabilitySlot(1L, START, START.plusMinutes(30), SlotStatus.OPEN);
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider));

        AvailabilitySlotResponse response = service.reserve(10L);

        assertThat(response.status()).isEqualTo(SlotStatus.BOOKED);
        verify(slotRepository).saveAndFlush(slot);
    }

    @Test
    void reserveRejectsSlotThatIsAlreadyBooked() {
        AvailabilitySlot slot = new AvailabilitySlot(1L, START, START.plusMinutes(30), SlotStatus.BOOKED);
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.reserve(10L))
                .isInstanceOf(SlotAlreadyTakenException.class);
        verify(slotRepository, never()).saveAndFlush(any());
    }

    @Test
    void reservePropagatesOptimisticLockFailureForTheAdviceToMapTo409() {
        AvailabilitySlot slot = new AvailabilitySlot(1L, START, START.plusMinutes(30), SlotStatus.OPEN);
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(slot))
                .thenThrow(new ObjectOptimisticLockingFailureException(AvailabilitySlot.class, 10L));

        assertThatThrownBy(() -> service.reserve(10L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void reserveMissingSlotIs404() {
        when(slotRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void releaseFlipsBookedSlotBackToOpen() {
        AvailabilitySlot slot = new AvailabilitySlot(1L, START, START.plusMinutes(30), SlotStatus.BOOKED);
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider));

        AvailabilitySlotResponse response = service.release(10L);

        assertThat(response.status()).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void releaseRejectsAnOpenSlot() {
        AvailabilitySlot slot = new AvailabilitySlot(1L, START, START.plusMinutes(30), SlotStatus.OPEN);
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.release(10L))
                .isInstanceOf(SlotNotBookedException.class);
    }

    @Test
    void searchRejectsAnInvertedRangeBeforeHittingTheDatabase() {
        assertThatThrownBy(() -> service.search("Cardiology", null, START, START.minusDays(1), 0, 20))
                .isInstanceOf(InvalidRequestException.class);
        verify(slotRepository, never()).searchOpenSlots(any(), any(), any(), any(), any());
    }
}
