package com.clinicscheduler.appointment.api;

import com.clinicscheduler.appointment.api.dto.LoginRequest;
import com.clinicscheduler.appointment.api.dto.LoginResponse;
import com.clinicscheduler.appointment.security.AuthUser;
import com.clinicscheduler.appointment.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Demo identity provider. Two fixed users stand in for a real IdP; no passwords or PII are
 * stored in the database for this demo. Passwords are documented in the README.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private record DemoAccount(String email, String password, String name, String role, String patientId) {
    }

    private static final Map<String, DemoAccount> ACCOUNTS = Map.of(
            "staff@clinic.test",
            new DemoAccount("staff@clinic.test", "staff123", "Sam Staff", "STAFF", null),
            "patient@clinic.test",
            new DemoAccount("patient@clinic.test", "patient123", "Pat Patient", "PATIENT", "patient-1"));

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        DemoAccount account = ACCOUNTS.get(request.email().trim().toLowerCase());
        if (account == null || !constantTimeEquals(account.password(), request.password())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        AuthUser user = new AuthUser(account.email(), account.name(), account.role(), account.patientId());
        String token = jwtService.createToken(user);
        return ResponseEntity.ok(new LoginResponse(
                token,
                "Bearer",
                jwtService.expirationSeconds(),
                new LoginResponse.UserInfo(user.email(), user.name(), user.role(), user.patientId())));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
