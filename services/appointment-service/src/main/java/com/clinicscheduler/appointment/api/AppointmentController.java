package com.clinicscheduler.appointment.api;

import com.clinicscheduler.appointment.api.dto.AppointmentResponse;
import com.clinicscheduler.appointment.api.dto.BookingRequest;
import com.clinicscheduler.appointment.security.AuthUser;
import com.clinicscheduler.appointment.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final BookingService bookingService;

    public AppointmentController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookingRequest request,
                                                    @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.ok(bookingService.book(request, user));
    }

    @GetMapping
    public List<AppointmentResponse> list(@RequestParam(required = false) String patientId,
                                          @AuthenticationPrincipal AuthUser user) {
        return bookingService.list(patientId, user);
    }

    @GetMapping("/{id}")
    public AppointmentResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return bookingService.get(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable Long id,
                                                      @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.status(HttpStatus.OK).body(bookingService.cancel(id, user));
    }
}
