package com.clinicscheduler.appointment.api;

import com.clinicscheduler.appointment.config.RestAccessDeniedHandler;
import com.clinicscheduler.appointment.config.RestAuthenticationEntryPoint;
import com.clinicscheduler.appointment.config.SecurityConfig;
import com.clinicscheduler.appointment.security.JwtAuthFilter;
import com.clinicscheduler.appointment.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long!!",
        "app.jwt.expiration-seconds=3600"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void staffLoginIssuesAStaffToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@clinic.test\",\"password\":\"staff123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("STAFF"));
    }

    @Test
    void patientLoginCarriesThePatientIdClaim() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"patient@clinic.test\",\"password\":\"patient123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("PATIENT"))
                .andExpect(jsonPath("$.user.patientId").value("patient-1"));
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@clinic.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void invalidEmailIs400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"staff123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void blankPasswordIs400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@clinic.test\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
