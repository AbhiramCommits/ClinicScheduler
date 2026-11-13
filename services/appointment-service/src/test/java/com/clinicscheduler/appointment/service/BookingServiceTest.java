package com.clinicscheduler.appointment.service;

import com.clinicscheduler.appointment.api.dto.AppointmentResponse;
import com.clinicscheduler.appointment.api.dto.BookingRequest;
import com.clinicscheduler.appointment.client.ProviderClient;
import com.clinicscheduler.appointment.client.SlotTakenException;
import com.clinicscheduler.appointment.domain.Appointment;
import com.clinicscheduler.appointment.domain.AppointmentStatus;
import com.clinicscheduler.appointment.error.ConflictException;
import com.clinicscheduler.appointment.error.DoubleBookingException;
import com.clinicscheduler.appointment.error.InvalidBookingException;
import com.clinicscheduler.appointment.error.ResourceNotFoundException;
import com.clinicscheduler.appointment.repo.AppointmentRepository;
import com.clinicscheduler.appointment.security.AuthUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 7, 1, 9, 0);
    private static final AuthUser STAFF = new AuthUser("staff@clinic.test", "Sam Staff", "STAFF", null);
    private static final AuthUser PATIENT_ONE =
            new AuthUser("patient@clinic.test", "Pat Patient", "PATIENT", "patient-1");

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private ProviderClient providerClient;

    @InjectMocks
    private BookingService service;

    private BookingRequest request(String patientId) {
        return new BookingRequest(patientId, "Pat Patient", 7L, 101L, START, START.plusMinutes(30));
    }

    private Appointment appointment(String patientId) {
        return new Appointment(patientId, "Pat Patient", 7L, 101L, START, START.plusMinutes(30));
    }

    @Test
    void bookReservesTheSlotThenInsertsTheAppointment() {
        Appointment saved = appointment("patient-1");
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenReturn(saved);

        AppointmentResponse response = service.book(request("patient-1"), PATIENT_ONE);

        assertThat(response.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(response.slotId()).isEqualTo(101L);
        verify(providerClient).reserve(101L);
        verify(appointmentRepository).saveAndFlush(any(Appointment.class));
    }

    @Test
    void aProvider409BecomesDoubleBookingAndNothingIsInserted() {
        doThrow(new SlotTakenException(101L)).when(providerClient).reserve(101L);

        assertThatThrownBy(() -> service.book(request("patient-1"), PATIENT_ONE))
                .isInstanceOf(DoubleBookingException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void aDuplicateKeyCompensatesByReleasingTheSlot() {
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_slot_active"));

        assertThatThrownBy(() -> service.book(request("patient-1"), PATIENT_ONE))
                .isInstanceOf(DoubleBookingException.class);
        verify(providerClient).release(101L);
    }

    @Test
    void aFailedCompensationStillReturnsTheOriginalConflict() {
        when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_slot_active"));
        doThrow(new RuntimeException("provider down")).when(providerClient).release(101L);

        assertThatThrownBy(() -> service.book(request("patient-1"), PATIENT_ONE))
                .isInstanceOf(DoubleBookingException.class);
    }

    @Test
    void invalidTimeRangeIsRejectedBeforeReserving() {
        BookingRequest invalid =
                new BookingRequest("patient-1", "Pat", 7L, 101L, START, START.minusMinutes(30));

        assertThatThrownBy(() -> service.book(invalid, PATIENT_ONE))
                .isInstanceOf(InvalidBookingException.class);
        verifyNoInteractions(providerClient);
    }

    @Test
    void patientCannotBookForAnotherPatient() {
        assertThatThrownBy(() -> service.book(request("patient-999"), PATIENT_ONE))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(providerClient);
    }

    @Test
    void cancelMarksCancelledAndReleasesTheSlot() {
        Appointment existing = appointment("patient-1");
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(appointmentRepository.saveAndFlush(existing)).thenReturn(existing);

        AppointmentResponse response = service.cancel(1L, PATIENT_ONE);

        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(providerClient).release(101L);
    }

    @Test
    void cancellingAnAlreadyCancelledAppointmentIs409() {
        Appointment existing = appointment("patient-1");
        existing.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancel(1L, PATIENT_ONE))
                .isInstanceOf(ConflictException.class);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void patientsOnlySeeTheirOwnAppointment() {
        Appointment existing = appointment("patient-2");
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.get(1L, PATIENT_ONE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void patientsListOnlyTheirOwnPatientId() {
        when(appointmentRepository.findByPatientIdOrderByStartTimeDesc("patient-1"))
                .thenReturn(List.of(appointment("patient-1")));

        List<AppointmentResponse> result = service.list(null, PATIENT_ONE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).patientId()).isEqualTo("patient-1");
    }

    @Test
    void patientsCannotFilterOnAnotherPatientId() {
        assertThatThrownBy(() -> service.list("patient-2", PATIENT_ONE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCanListAllOrFilterByPatient() {
        when(appointmentRepository.findAllByOrderByStartTimeDesc())
                .thenReturn(List.of(appointment("patient-1"), appointment("patient-2")));
        when(appointmentRepository.findByPatientIdOrderByStartTimeDesc("patient-2"))
                .thenReturn(List.of(appointment("patient-2")));

        assertThat(service.list(null, STAFF)).hasSize(2);
        assertThat(service.list("patient-2", STAFF)).hasSize(1);
    }

    @Test
    void missingAppointmentIs404() {
        when(appointmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L, STAFF))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
