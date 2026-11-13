import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { AvailabilitySearchComponent } from './availability-search.component';
import { ProviderApiService } from '../../core/services/provider-api.service';
import { AvailabilitySlot, PageResponse } from '../../core/models/api.models';

@Component({ template: '' })
class EmptyComponent {}

const slot: AvailabilitySlot = {
  id: 11,
  providerId: 7,
  providerName: 'Dr. Ada Cardiology',
  specialty: 'Cardiology',
  clinicLocation: 'Downtown Clinic',
  startTime: '2030-03-01T09:00:00',
  endTime: '2030-03-01T09:30:00',
  status: 'OPEN',
};

describe('AvailabilitySearchComponent', () => {
  let providerApi: { searchAvailability: jasmine.Spy };
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    const page: PageResponse<AvailabilitySlot> = {
      content: [slot],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    };
    providerApi = {
      searchAvailability: jasmine.createSpy('searchAvailability').and.returnValue(of(page)),
    };

    await TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'search', component: AvailabilitySearchComponent },
          { path: 'book', component: EmptyComponent },
          { path: 'appointments', component: EmptyComponent },
        ]),
        { provide: ProviderApiService, useValue: providerApi },
      ],
    }).compileComponents();

    harness = await RouterTestingHarness.create('/search');
  });

  function component(): AvailabilitySearchComponent {
    return harness.routeDebugElement!.componentInstance as AvailabilitySearchComponent;
  }

  it('loads availability on init and renders slot cards', () => {
    const form = component();
    harness.fixture.detectChanges();

    expect(providerApi.searchAvailability).toHaveBeenCalledTimes(1);
    const text = (harness.fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Dr. Ada Cardiology');
    expect(form.totalElements).toBe(1);
  });

  it('flags an inverted date range as invalid and blocks searching', () => {
    const form = component();
    form.form.patchValue({ from: '2030-03-20', to: '2030-03-01' });
    harness.fixture.detectChanges();

    expect(form.form.hasError('dateRange')).toBeTrue();
    expect(form.form.invalid).toBeTrue();

    const submit = (harness.fixture.nativeElement as HTMLElement).querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBeTrue();

    form.search();
    expect(providerApi.searchAvailability).toHaveBeenCalledTimes(1);
  });

  it('accepts a valid range and searches again', () => {
    const form = component();
    form.form.patchValue({ from: '2030-03-01', to: '2030-03-31' });
    form.search();

    expect(form.form.valid).toBeTrue();
    expect(providerApi.searchAvailability).toHaveBeenCalledTimes(2);
    const lastCall = providerApi.searchAvailability.calls.mostRecent().args[0] as {
      from: string;
      to: string;
      page: number;
    };
    expect(lastCall.from).toBe('2030-03-01T00:00:00');
    expect(lastCall.to).toBe('2030-03-31T23:59:59');
  });

  it('renders an empty state when no slots are available', () => {
    providerApi.searchAvailability.and.returnValue(
      of({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
    );
    const form = component();
    form.search();
    harness.fixture.detectChanges();

    const text = (harness.fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No times match');
  });
});
