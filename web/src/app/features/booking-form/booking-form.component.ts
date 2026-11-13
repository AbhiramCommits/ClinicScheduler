import { Component, OnInit, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { AppointmentApiService } from '../../core/services/appointment-api.service';
import { ProviderApiService } from '../../core/services/provider-api.service';
import { AuthService } from '../../core/services/auth.service';
import {
  ApiError,
  Appointment,
  AvailabilitySlot,
  BookingRequest,
} from '../../core/models/api.models';

@Component({
  selector: 'app-booking-form',
  imports: [ReactiveFormsModule, DatePipe, RouterLink],
  templateUrl: './booking-form.component.html',
  styleUrl: './booking-form.component.scss',
})
export class BookingFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly appointmentApi = inject(AppointmentApiService);
  private readonly providerApi = inject(ProviderApiService);
  readonly auth = inject(AuthService);

  slotId = 0;
  providerId = 0;
  providerName = '';
  specialty = '';
  clinicLocation = '';
  startTime = '';
  endTime = '';
  fromDate = '';
  toDate = '';

  submitting = false;
  conflict = false;
  errorMessage = '';
  confirmed: Appointment | null = null;
  alternatives: AvailabilitySlot[] = [];
  slotStillAvailable = true;
  loadingAlternatives = false;

  readonly form = this.fb.nonNullable.group({
    patientId: ['', [Validators.required, Validators.maxLength(64)]],
    patientName: ['', [Validators.required, Validators.maxLength(120)]],
  });

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => this.applyParams(params));
  }

  submit(): void {
    if (this.submitting) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const request: BookingRequest = {
      patientId: raw.patientId,
      patientName: raw.patientName,
      providerId: this.providerId,
      slotId: this.slotId,
      startTime: this.startTime,
      endTime: this.endTime,
    };

    this.submitting = true;
    this.errorMessage = '';
    this.conflict = false;

    this.appointmentApi.book(request).subscribe({
      next: (appointment) => {
        this.submitting = false;
        this.confirmed = appointment;
      },
      error: (error: HttpErrorResponse) => {
        this.submitting = false;
        const code = (error.error as ApiError | null)?.error;
        if (
          error.status === 409 &&
          (code === 'DOUBLE_BOOKING_BLOCKED' || code === 'SLOT_ALREADY_TAKEN')
        ) {
          this.conflict = true;
          this.confirmed = null;
          this.refreshSlots();
        } else if (error.status === 403) {
          this.errorMessage = 'You can only book appointments for your own patient record.';
        } else if (error.status === 401) {
          this.errorMessage = 'Your session expired. Please sign in again.';
        } else if (error.status === 503) {
          this.errorMessage = 'The scheduling service is briefly unavailable. Please try again.';
        } else {
          this.errorMessage = 'The appointment could not be booked. Please try again.';
        }
      },
    });
  }

  useAlternative(slot: AvailabilitySlot): void {
    void this.router.navigate(['/book'], {
      queryParams: {
        slotId: slot.id,
        providerId: slot.providerId,
        providerName: slot.providerName,
        specialty: slot.specialty,
        clinicLocation: slot.clinicLocation,
        startTime: slot.startTime,
        endTime: slot.endTime,
        from: this.fromDate,
        to: this.toDate,
      },
    });
  }

  cancelEditing(): void {
    void this.router.navigate(['/search']);
  }

  private applyParams(params: ParamMap): void {
    const slotId = params.get('slotId');
    if (!slotId) {
      void this.router.navigate(['/search']);
      return;
    }
    this.slotId = Number(slotId);
    this.providerId = Number(params.get('providerId') ?? 0);
    this.providerName = params.get('providerName') ?? 'Selected provider';
    this.specialty = params.get('specialty') ?? '';
    this.clinicLocation = params.get('clinicLocation') ?? '';
    this.startTime = params.get('startTime') ?? '';
    this.endTime = params.get('endTime') ?? '';
    this.fromDate = params.get('from') ?? this.startTime.slice(0, 10);
    this.toDate = params.get('to') ?? this.startTime.slice(0, 10);

    this.confirmed = null;
    this.conflict = false;
    this.errorMessage = '';
    this.alternatives = [];
    this.slotStillAvailable = true;
    this.prefillPatient();
  }

  private prefillPatient(): void {
    if (this.auth.role() !== 'PATIENT') {
      return;
    }
    this.form.setValue({
      patientId: this.auth.patientId() ?? '',
      patientName: this.auth.user()?.name ?? '',
    });
    this.form.controls.patientId.disable();
    this.form.controls.patientName.disable();
  }

  private refreshSlots(): void {
    this.loadingAlternatives = true;
    this.providerApi
      .searchAvailability({
        specialty: this.specialty,
        location: this.clinicLocation,
        from: `${this.fromDate}T00:00:00`,
        to: `${this.toDate}T23:59:59`,
        page: 0,
        size: 20,
      })
      .subscribe({
        next: (result) => {
          this.loadingAlternatives = false;
          this.alternatives = result.content.filter((slot) => slot.id !== this.slotId);
          this.slotStillAvailable = result.content.some((slot) => slot.id === this.slotId);
        },
        error: () => {
          this.loadingAlternatives = false;
        },
      });
  }
}
