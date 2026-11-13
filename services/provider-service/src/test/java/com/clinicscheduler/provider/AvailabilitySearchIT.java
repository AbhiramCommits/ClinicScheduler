package com.clinicscheduler.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the real application against MySQL 8 in Testcontainers. Flyway migrates an empty
 * schema to head on context start, then each test seeds 240 slots and exercises the real
 * search/reserve SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AvailabilitySearchIT {

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

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 7, 9, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Long cardiologyDowntownId;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM availability_slots");
        jdbc.execute("DELETE FROM providers");

        jdbc.update("INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active) VALUES (?,?,?,?,?)",
                "Dr. Ada Cardiology", "Cardiology", "1000000001", "Downtown Clinic", true);
        jdbc.update("INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active) VALUES (?,?,?,?,?)",
                "Dr. Ben Cardiology", "Cardiology", "1000000002", "Northside Medical Center", true);
        jdbc.update("INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active) VALUES (?,?,?,?,?)",
                "Dr. Cara Derm", "Dermatology", "1000000003", "Downtown Clinic", true);
        jdbc.update("INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active) VALUES (?,?,?,?,?)",
                "Dr. Dan Derm", "Dermatology", "1000000004", "Northside Medical Center", true);

        List<Long> providerIds = jdbc.queryForList("SELECT id FROM providers ORDER BY id", Long.class);
        cardiologyDowntownId = providerIds.get(0);

        // 60 slots per provider x 4 providers = 240 slots; first 50 per provider are OPEN.
        jdbc.batchUpdate("INSERT INTO availability_slots (provider_id, start_time, end_time, status) VALUES (?,?,?,?)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws java.sql.SQLException {
                        int providerIndex = index / 60;
                        int slotIndex = index % 60;
                        int day = slotIndex / 2;
                        int hour = slotIndex % 2 == 0 ? 9 : 11;
                        LocalDateTime start = BASE.plusDays(day).withHour(hour);
                        ps.setLong(1, providerIds.get(providerIndex));
                        ps.setObject(2, start);
                        ps.setObject(3, start.plusMinutes(30));
                        ps.setString(4, slotIndex < 50 ? "OPEN" : "BOOKED");
                    }

                    @Override
                    public int getBatchSize() {
                        return 240;
                    }
                });

    }

    @Test
    void searchFiltersBySpecialtyLocationStatusAndRange() throws Exception {
        mockMvc.perform(get("/api/availability/search")
                        .param("specialty", "Cardiology")
                        .param("location", "Downtown Clinic")
                        .param("from", BASE.minusHours(1).format(ISO))
                        .param("to", BASE.plusDays(30).format(ISO))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(50))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.content[0].specialty").value("Cardiology"))
                .andExpect(jsonPath("$.content[0].clinicLocation").value("Downtown Clinic"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].startTime").value(BASE.format(ISO)))
                .andExpect(jsonPath("$.content[1].startTime").value(BASE.plusHours(2).format(ISO)));
    }

    @Test
    void searchPaginatesWithoutDuplicates() throws Exception {
        mockMvc.perform(get("/api/availability/search")
                        .param("specialty", "Dermatology")
                        .param("from", BASE.minusHours(1).format(ISO))
                        .param("to", BASE.plusDays(30).format(ISO))
                        .param("page", "4")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.content[9].startTime").value(BASE.plusDays(12).withHour(9).format(ISO)));
    }

    @Test
    void searchRejectsInvertedRange() throws Exception {
        mockMvc.perform(get("/api/availability/search")
                        .param("from", BASE.plusDays(5).format(ISO))
                        .param("to", BASE.format(ISO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void reserveWithoutTokenIsUnauthorized() throws Exception {
        Long openSlotId = jdbc.queryForObject("SELECT id FROM availability_slots LIMIT 1", Long.class);
        mockMvc.perform(post("/api/availability/{id}/reserve", openSlotId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void sequentialReserveReturns200Then409() throws Exception {
        Long openSlotId = jdbc.queryForObject("""
                SELECT id FROM availability_slots
                WHERE provider_id = ? AND status = 'OPEN'
                ORDER BY start_time LIMIT 1
                """, Long.class, cardiologyDowntownId);

        mockMvc.perform(post("/api/availability/{id}/reserve", openSlotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"));

        mockMvc.perform(post("/api/availability/{id}/reserve", openSlotId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_ALREADY_TAKEN"));

        String status = jdbc.queryForObject("SELECT status FROM availability_slots WHERE id = ?",
                String.class, openSlotId);
        assertThat(status).isEqualTo("BOOKED");
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void releaseMakesTheSlotOpenAgain() throws Exception {
        Long openSlotId = jdbc.queryForObject("""
                SELECT id FROM availability_slots
                WHERE provider_id = ? AND status = 'OPEN'
                ORDER BY start_time LIMIT 1
                """, Long.class, cardiologyDowntownId);

        mockMvc.perform(post("/api/availability/{id}/reserve", openSlotId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/availability/{id}/release", openSlotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/availability/{id}/release", openSlotId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_NOT_BOOKED"));
    }

    @Test
    void providerLookupAndListingWork() throws Exception {
        mockMvc.perform(get("/api/providers").param("specialty", "Cardiology").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].fullName").value("Dr. Ada Cardiology"));

        mockMvc.perform(get("/api/providers/{id}", cardiologyDowntownId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.npiNumber").value("1000000001"));

        mockMvc.perform(get("/api/providers/{id}/availability", cardiologyDowntownId)
                        .param("from", BASE.minusHours(1).format(ISO))
                        .param("to", BASE.plusDays(30).format(ISO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50));
    }
}
