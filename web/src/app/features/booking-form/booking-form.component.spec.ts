import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { BookingFormComponent } from './booking-form.component';
import { AppointmentApiService } from '../../core/services/appointment-api.service';
import { ProviderApiService } from '../../core/services/provider-api.service';
import { AuthService } from '../../core/services/auth.service';
import { Appointment, PageResponse } from '../../core/models/api.models';

@Component({ template: '' })
class EmptyComponent {}

const emptyPage: PageResponse<never> = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

const appointment: Appointment = {
  id: 42,
  patientId: 'patient-1',
  patientName: 'Pat Patient',
  providerId: 7,
  slotId: 101,
  startTime: '2030-03-01T09:00:00',
  endTime: '2030-03-01T09:30:00',
  status: 'BOOKED',
  version: 0,
  createdAt: '2030-02-01T00:00:00Z',
  updatedAt: '2030-02-01T00:00:00Z',
};

const query =
  'slotId=101&providerId=7&providerName=Dr.%20Ada%20Cardiology&specialty=Cardiology' +
  '&clinicLocation=Downtown%20Clinic&startTime=2030-03-01T09%3A00%3A00' +
  '&endTime=2030-03-01T09%3A30%3A00&from=2030-03-01&to=2030-03-31';

describe('BookingFormComponent', () => {
  let appointmentApi: { book: jasmine.Spy };
  let providerApi: { searchAvailability: jasmine.Spy };
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    appointmentApi = {
      book: jasmine.createSpy('book').and.returnValue(throwError(() => new HttpErrorResponse({
        status: 409,
        error: { error: 'DOUBLE_BOOKING_BLOCKED', message: 'taken' },
      }))),
    };
    providerApi = {
      searchAvailability: jasmine.createSpy('searchAvailability').and.returnValue(of(emptyPage)),
    };

    await TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'book', component: BookingFormComponent },
          { path: 'search', component: EmptyComponent },
          { path: 'appointments', component: EmptyComponent },
        ]),
        { provide: AppointmentApiService, useValue: appointmentApi },
        { provide: ProviderApiService, useValue: providerApi },
        {
          provide: AuthService,
          useValue: {
            role: () => 'STAFF',
            patientId: () => null,
            user: () => ({
              email: 'staff@clinic.test',
              name: 'Sam Staff',
              role: 'STAFF',
              patientId: null,
            }),
          },
        },
      ],
    }).compileComponents();

    harness = await RouterTestingHarness.create(`/book?${query}`);
  });

  function component(): BookingFormComponent {
    return harness.routeDebugElement!.componentInstance as BookingFormComponent;
  }

  it('renders an inline conflict banner on 409 and refreshes the slot list', () => {
    const form = component();
    form.form.setValue({ patientId: 'patient-1', patientName: 'Pat Patient' });

    form.submit();
    harness.fixture.detectChanges();

    expect(appointmentApi.book).toHaveBeenCalled();
    expect(form.conflict).toBeTrue();
    expect(providerApi.searchAvailability).toHaveBeenCalled();

    const text = (harness.fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('That slot was just taken');
    expect(text).toContain('pick another time');
  });

  it('shows a confirmation for a successful booking instead of the form', () => {
    appointmentApi.book.and.returnValue(of(appointment));
    const form = component();
    form.form.setValue({ patientId: 'patient-1', patientName: 'Pat Patient' });

    form.submit();
    harness.fixture.detectChanges();

    expect(form.confirmed).toEqual(appointment);
    const text = (harness.fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Appointment confirmed');
    expect(text).not.toContain('That slot was just taken');
  });

  it('does not submit an incomplete patient form', () => {
    const form = component();
    form.form.reset();
    form.submit();
    expect(appointmentApi.book).not.toHaveBeenCalled();
  });
});
