package com.clinicscheduler.appointment;

import com.clinicscheduler.appointment.api.dto.AppointmentResponse;
import com.clinicscheduler.appointment.api.dto.BookingRequest;
import com.clinicscheduler.appointment.error.DoubleBookingException;
import com.clinicscheduler.appointment.security.AuthUser;
import com.clinicscheduler.appointment.service.BookingService;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scaled-down (N=20) in-CI version of the double-booking harness. The provider stub answers every
 * reserve with 200, so this test proves the appointment database's uk_slot_active constraint is
 * the final authority: 20 simultaneous bookings for the same slot produce exactly one row, and
 * the 19 losers each compensate by releasing their reservation.
 */
@SpringBootTest
@Testcontainers
class ConcurrencyIT {

    private static final int CONCURRENCY = 20;
    private static final long SLOT_ID = 5001L;
    private static final LocalDateTime START = LocalDateTime.of(2031, 1, 5, 9, 0);
    private static final AuthUser STAFF =
            new AuthUser("staff@clinic.test", "Sam Staff", "STAFF", null);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("appointment_db")
            .withUsername("clinic")
            .withPassword("clinic_pass");

    static final WireMockServer PROVIDER = new WireMockServer(0);

    static {
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.provider-service.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        PROVIDER.stop();
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM appointments");
        PROVIDER.resetAll();
        PROVIDER.stubFor(post(urlPathMatching("/api/availability/\\d+/reserve"))
                .willReturn(aResponse().withStatus(200)));
        PROVIDER.stubFor(post(urlPathMatching("/api/availability/\\d+/release"))
                .willReturn(aResponse().withStatus(200)));
    }

    @Test
    void twentyConcurrentBookingsForOneSlotProduceOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    BookingRequest request = new BookingRequest(
                            "race-patient-" + index,
                            "Race Patient " + index,
                            7L,
                            SLOT_ID,
                            START,
                            START.plusMinutes(30));
                    bookingService.book(request, STAFF);
                    successes.incrementAndGet();
                } catch (DoubleBookingException expected) {
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
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE slot_id = ?", Integer.class, SLOT_ID);
        assertThat(rows).isEqualTo(1);
        PROVIDER.verify(CONCURRENCY - 1,
                postRequestedFor(urlPathMatching("/api/availability/" + SLOT_ID + "/release")));
    }
}
