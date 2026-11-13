package com.clinicscheduler.appointment.repo;

import com.clinicscheduler.appointment.domain.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientIdOrderByStartTimeDesc(String patientId);

    List<Appointment> findAllByOrderByStartTimeDesc();

    Optional<Appointment> findBySlotId(Long slotId);

    boolean existsByPatientIdAndStartTime(String patientId, java.time.LocalDateTime startTime);
}
