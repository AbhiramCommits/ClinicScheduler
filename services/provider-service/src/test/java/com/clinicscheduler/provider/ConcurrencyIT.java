package com.clinicscheduler.provider;

import com.clinicscheduler.provider.domain.SlotStatus;
import com.clinicscheduler.provider.error.SlotAlreadyTakenException;
import com.clinicscheduler.provider.service.AvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scaled-down (N=20) in-CI version of the 50-way concurrency harness. Twenty threads reserve
 * the same OPEN slot through the real transactional service and Testcontainers MySQL; exactly
 * one must win, whether it wins by reading OPEN and committing or by holding the optimistic
 * lock against the others.
 */
@SpringBootTest
@Testcontainers
class ConcurrencyIT {

    private static final int CONCURRENCY = 20;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("provider_db")
            .withUsername("clinic")
            .withPassword("clinic_pass");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long slotId;

    @BeforeEach
    void seedOneOpenSlot() {
        jdbc.execute("DELETE FROM availability_slots");
        jdbc.execute("DELETE FROM providers");
        jdbc.update("INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active) VALUES (?,?,?,?,?)",
                "Dr. Race", "Cardiology", "1999999999", "Downtown Clinic", true);
        Long providerId = jdbc.queryForObject("SELECT id FROM providers LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO availability_slots (provider_id, start_time, end_time, status)
                VALUES (?, '2030-06-01 09:00:00', '2030-06-01 09:30:00', 'OPEN')
                """, providerId);
        slotId = jdbc.queryForObject("SELECT id FROM availability_slots LIMIT 1", Long.class);
    }

    @Test
    void twentyConcurrentReservesProduceExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    availabilityService.reserve(slotId);
                    successes.incrementAndGet();
                } catch (SlotAlreadyTakenException | OptimisticLockingFailureException expected) {
                    conflicts.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected.get()).isNull();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(CONCURRENCY - 1);
        String status = jdbc.queryForObject("SELECT status FROM availability_slots WHERE id = ?",
                String.class, slotId);
        Long version = jdbc.queryForObject("SELECT version FROM availability_slots WHERE id = ?",
                Long.class, slotId);
        assertThat(status).isEqualTo(SlotStatus.BOOKED.name());
        assertThat(version).isEqualTo(1L);
    }
}
