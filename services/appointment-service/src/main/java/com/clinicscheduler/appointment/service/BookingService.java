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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final AppointmentRepository appointmentRepository;
    private final ProviderClient providerClient;

    public BookingService(AppointmentRepository appointmentRepository, ProviderClient providerClient) {
        this.appointmentRepository = appointmentRepository;
        this.providerClient = providerClient;
    }

    /**
     * Reserve-then-insert-then-compensate:
     * 1. ask provider-service to flip OPEN -> BOOKED,
     * 2. insert the appointment row, relying on uk_slot_active as the database authority,
     * 3. if the insert violates a unique key, release the reservation and return a conflict.
     */
    @Transactional
    public AppointmentResponse book(BookingRequest request, AuthUser user) {
        requireOwnerOrStaff(request.patientId(), user);
        if (!request.endTime().isAfter(request.startTime())) {
            throw new InvalidBookingException("endTime must be after startTime");
        }

        try {
            providerClient.reserve(request.slotId());
        } catch (SlotTakenException ex) {
            throw new DoubleBookingException();
        }

        try {
            Appointment appointment = new Appointment(
                    request.patientId(),
                    request.patientName(),
                    request.providerId(),
                    request.slotId(),
                    request.startTime(),
                    request.endTime());
            Appointment saved = appointmentRepository.saveAndFlush(appointment);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Appointment insert rejected by a unique key for slot {}: {}",
                    request.slotId(), ex.getMostSpecificCause().getMessage());
            releaseQuietly(request.slotId());
            throw new DoubleBookingException();
        }
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> list(String patientId, AuthUser user) {
        if (user.isStaff()) {
            return (patientId == null || patientId.isBlank()
                    ? appointmentRepository.findAllByOrderByStartTimeDesc()
                    : appointmentRepository.findByPatientIdOrderByStartTimeDesc(patientId))
                    .stream().map(this::toResponse).toList();
        }
        if (patientId != null && !patientId.isBlank() && !patientId.equals(user.patientId())) {
            throw new AccessDeniedException("Patients may only read their own appointments");
        }
        return appointmentRepository.findByPatientIdOrderByStartTimeDesc(user.patientId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(Long id, AuthUser user) {
        Appointment appointment = find(id);
        requireOwnerOrStaff(appointment.getPatientId(), user);
        return toResponse(appointment);
    }

    @Transactional
    public AppointmentResponse cancel(Long id, AuthUser user) {
        Appointment appointment = find(id);
        requireOwnerOrStaff(appointment.getPatientId(), user);
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ConflictException("APPOINTMENT_NOT_ACTIVE", "Appointment is already cancelled or completed");
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.saveAndFlush(appointment);
        releaseQuietly(saved.getSlotId());
        return toResponse(saved);
    }

    private Appointment find(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment " + id + " not found"));
    }

    private void requireOwnerOrStaff(String patientId, AuthUser user) {
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (!user.isStaff() && !patientId.equals(user.patientId())) {
            throw new AccessDeniedException("A patient may only act on their own appointments");
        }
    }

    private void releaseQuietly(Long slotId) {
        try {
            providerClient.release(slotId);
        } catch (RuntimeException ex) {
            // The booking has already been rejected/cancelled; keep the original outcome and log
            // the failed compensation so operators can reconcile the slot.
            log.error("Compensating release failed for slot {}; slot may remain BOOKED", slotId, ex);
        }
    }

    private AppointmentResponse toResponse(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getPatientId(),
                appointment.getPatientName(),
                appointment.getProviderId(),
                appointment.getSlotId(),
                appointment.getStartTime(),
                appointment.getEndTime(),
                appointment.getStatus(),
                appointment.getVersion(),
                appointment.getCreatedAt(),
                appointment.getUpdatedAt());
    }
}
