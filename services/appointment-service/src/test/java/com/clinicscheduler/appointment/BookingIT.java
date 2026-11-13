package com.clinicscheduler.appointment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full booking workflow against MySQL 8 in Testcontainers, with WireMock standing in for
 * provider-service. Covers the database unique-constraint path because WireMock always answers
 * the reserve call with 200: the second insert must be rejected by uk_slot_active and compensated
 * with a release call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BookingIT {

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String staffToken;
    private String patientToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("DELETE FROM appointments");
        PROVIDER.resetAll();
        PROVIDER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/api/availability/\\d+/reserve"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"status\":\"BOOKED\"}")));
        PROVIDER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/api/availability/\\d+/release"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"status\":\"OPEN\"}")));

        staffToken = login("staff@clinic.test", "staff123");
        patientToken = login("patient@clinic.test", "patient123");
    }

    @Test
    void happyPathBookingCreatesAppointmentAndCallsReserve() throws Exception {
        String body = """
                {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
                 "slotId":101,"startTime":"2030-03-01T09:00:00","endTime":"2030-03-01T09:30:00"}
                """;

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.slotId").value(101))
                .andExpect(jsonPath("$.patientId").value("patient-1"));

        PROVIDER.verify(postRequestedFor(urlEqualTo("/api/availability/101/reserve")));
    }

    @Test
    void duplicateSlotIsRejectedByUniqueKeyAndSlotIsReleased() throws Exception {
        String first = """
                {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
                 "slotId":202,"startTime":"2030-03-02T09:00:00","endTime":"2030-03-02T09:30:00"}
                """;
        String second = """
                {"patientId":"patient-2","patientName":"Other Patient","providerId":7,
                 "slotId":202,"startTime":"2030-03-02T09:00:00","endTime":"2030-03-02T09:30:00"}
                """;

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOUBLE_BOOKING_BLOCKED"))
                .andExpect(jsonPath("$.message").value(containsString("just taken")));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE slot_id = 202", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

        // The failed insert must compensate the successful reservation.
        PROVIDER.verify(1, postRequestedFor(urlEqualTo("/api/availability/202/release")));
    }

    @Test
    void cancelSetsCancelledAndReleasesTheSlot() throws Exception {
        String body = """
                {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
                 "slotId":303,"startTime":"2030-03-03T09:00:00","endTime":"2030-03-03T09:30:00"}
                """;
        String response = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(delete("/api/appointments/{id}", id)
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        PROVIDER.verify(postRequestedFor(urlEqualTo("/api/availability/303/release")));
    }

    @Test
    void unauthenticatedBookingIs401() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
                                 "slotId":404,"startTime":"2030-03-04T09:00:00","endTime":"2030-03-04T09:30:00"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        PROVIDER.verify(0, postRequestedFor(urlPathMatching("/api/availability/.*/reserve")));
    }

    @Test
    void patientCannotBookForAnotherPatient() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"patient-999","patientName":"Someone Else","providerId":7,
                                 "slotId":505,"startTime":"2030-03-05T09:00:00","endTime":"2030-03-05T09:30:00"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        PROVIDER.verify(0, postRequestedFor(urlPathMatching("/api/availability/.*/reserve")));
    }

    @Test
    void validationFailureIs400() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"\",\"slotId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void wrongLoginIs401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@clinic.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void staffCanListAllAndPatientOnlyTheirOwn() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
                                 "slotId":606,"startTime":"2030-03-06T09:00:00","endTime":"2030-03-06T09:30:00"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/appointments").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/appointments")
                        .param("patientId", "patient-1")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/appointments")
                        .param("patientId", "patient-other")
                        .header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
    }

    private String login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("token").asText();
    }
}
