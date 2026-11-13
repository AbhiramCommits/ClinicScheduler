import { Component, OnInit, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AppointmentApiService } from '../../core/services/appointment-api.service';
import { AuthService } from '../../core/services/auth.service';
import { Appointment } from '../../core/models/api.models';

@Component({
  selector: 'app-appointment-list',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  templateUrl: './appointment-list.component.html',
  styleUrl: './appointment-list.component.scss',
})
export class AppointmentListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly appointmentApi = inject(AppointmentApiService);
  private readonly auth = inject(AuthService);

  readonly isStaff = this.auth.role() === 'STAFF';
  readonly filterControl = this.fb.nonNullable.control('');

  appointments: Appointment[] = [];
  loading = false;
  errorMessage = '';
  cancellingId: number | null = null;
  noticeMessage = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.errorMessage = '';
    const patientId = this.isStaff ? this.filterControl.value.trim() || undefined : undefined;
    this.appointmentApi.list(patientId).subscribe({
      next: (appointments) => {
        this.loading = false;
        this.appointments = appointments;
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'Appointments could not be loaded. Please try again.';
      },
    });
  }

  cancel(appointment: Appointment): void {
    if (this.cancellingId !== null) {
      return;
    }
    this.cancellingId = appointment.id;
    this.errorMessage = '';
    this.noticeMessage = '';
    this.appointmentApi.cancel(appointment.id).subscribe({
      next: (updated) => {
        this.cancellingId = null;
        this.appointments = this.appointments.map((item) =>
          item.id === updated.id ? updated : item,
        );
        this.noticeMessage = 'Appointment cancelled and the time was released.';
      },
      error: (error: HttpErrorResponse) => {
        this.cancellingId = null;
        this.errorMessage =
          error.status === 409
            ? 'That appointment was already cancelled or completed.'
            : 'The appointment could not be cancelled. Please try again.';
      },
    });
  }

  statusClass(status: Appointment['status']): string {
    return `status status-${status.toLowerCase()}`;
  }
}
