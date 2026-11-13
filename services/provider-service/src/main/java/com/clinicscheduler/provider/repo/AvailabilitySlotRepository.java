package com.clinicscheduler.provider.repo;

import com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse;
import com.clinicscheduler.provider.domain.AvailabilitySlot;
import com.clinicscheduler.provider.domain.SlotStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {

    List<AvailabilitySlot> findByProviderIdAndStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
            Long providerId, SlotStatus status, LocalDateTime from, LocalDateTime to);

    @Query(value = """
            select new com.clinicscheduler.provider.api.dto.AvailabilitySlotResponse(
                s.id, p.id, p.fullName, p.specialty, p.clinicLocation, s.startTime, s.endTime, s.status)
            from AvailabilitySlot s, Provider p
            where p.id = s.providerId
              and s.status = com.clinicscheduler.provider.domain.SlotStatus.OPEN
              and (:specialty is null or p.specialty = :specialty)
              and (:location is null or p.clinicLocation = :location)
              and s.startTime >= :from
              and s.startTime < :to
            order by s.startTime asc, s.providerId asc
            """,
            countQuery = """
            select count(s)
            from AvailabilitySlot s, Provider p
            where p.id = s.providerId
              and s.status = com.clinicscheduler.provider.domain.SlotStatus.OPEN
              and (:specialty is null or p.specialty = :specialty)
              and (:location is null or p.clinicLocation = :location)
              and s.startTime >= :from
              and s.startTime < :to
            """)
    Page<AvailabilitySlotResponse> searchOpenSlots(@Param("specialty") String specialty,
                                                   @Param("location") String location,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to,
                                                   Pageable pageable);
}
