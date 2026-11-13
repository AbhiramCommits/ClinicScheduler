package com.clinicscheduler.provider.repo;

import com.clinicscheduler.provider.domain.Provider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {

    @Query("""
            select p from Provider p
            where (:specialty is null or p.specialty = :specialty)
              and (:location is null or p.clinicLocation = :location)
            """)
    Page<Provider> search(@Param("specialty") String specialty,
                          @Param("location") String location,
                          Pageable pageable);

    Optional<Provider> findByNpiNumber(String npiNumber);
}
