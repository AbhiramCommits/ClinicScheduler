package com.clinicscheduler.appointment.api;

import com.clinicscheduler.appointment.api.dto.AppointmentResponse;
import com.clinicscheduler.appointment.config.RestAccessDeniedHandler;
import com.clinicscheduler.appointment.config.RestAuthenticationEntryPoint;
import com.clinicscheduler.appointment.config.SecurityConfig;
import com.clinicscheduler.appointment.domain.AppointmentStatus;
import com.clinicscheduler.appointment.error.ConflictException;
import com.clinicscheduler.appointment.error.DoubleBookingException;
import com.clinicscheduler.appointment.error.ResourceNotFoundException;
import com.clinicscheduler.appointment.security.JwtAuthFilter;
import com.clinicscheduler.appointment.security.JwtService;
import com.clinicscheduler.appointment.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppointmentController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long!!")
class AppointmentControllerTest {

    private static final String VALID_BODY = """
            {"patientId":"patient-1","patientName":"Pat Patient","providerId":7,
             "slotId":101,"startTime":"2030-07-01T09:00:00","endTime":"2030-07-01T09:30:00"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;

    private final AppointmentResponse appointment = new AppointmentResponse(
            1L, "patient-1", "Pat Patient", 7L, 101L,
            LocalDateTime.of(2030, 7, 1, 9, 0), LocalDateTime.of(2030, 7, 1, 9, 30),
            AppointmentStatus.BOOKED, 0L,
            Instant.parse("2030-06-01T00:00:00Z"), Instant.parse("2030-06-01T00:00:00Z"));

    @Test
    void unauthenticatedWriteIs401() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void invalidBodyIs400() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"\",\"slotId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void bookingSucceeds() throws Exception {
        when(bookingService.book(any(), any())).thenReturn(appointment);

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void doubleBookingIs409() throws Exception {
        when(bookingService.book(any(), any())).thenThrow(new DoubleBookingException());

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOUBLE_BOOKING_BLOCKED"));
    }

    @Test
    @WithMockUser(username = "patient@clinic.test", roles = "PATIENT")
    void bookingForAnotherPatientIs403() throws Exception {
        when(bookingService.book(any(), any())).thenThrow(new AccessDeniedException("not yours"));

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void listReturnsAppointments() throws Exception {
        when(bookingService.list(isNull(), any())).thenReturn(List.of(appointment));

        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(username = "staff@clinic.test", roles = "STAFF")
    void missingAppointmentIs404() throws Exception {
        when(bookingService.get(eq(99L), any())).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/appointments/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "patient@clinic.test", roles = "PATIENT")
    void cancelReturnsCancelledAppointment() throws Exception {
        AppointmentResponse cancelled = new AppointmentResponse(
                1L, "patient-1", "Pat Patient", 7L, 101L,
                LocalDateTime.of(2030, 7, 1, 9, 0), LocalDateTime.of(2030, 7, 1, 9, 30),
                AppointmentStatus.CANCELLED, 1L,
                Instant.parse("2030-06-01T00:00:00Z"), Instant.parse("2030-06-02T00:00:00Z"));
        when(bookingService.cancel(eq(1L), any())).thenReturn(cancelled);

        mockMvc.perform(delete("/api/appointments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(username = "patient@clinic.test", roles = "PATIENT")
    void cancellingAnInactiveAppointmentIs409() throws Exception {
        when(bookingService.cancel(eq(1L), any()))
                .thenThrow(new ConflictException("APPOINTMENT_NOT_ACTIVE", "already cancelled"));

        mockMvc.perform(delete("/api/appointments/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_NOT_ACTIVE"));
    }
}
