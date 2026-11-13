import { Component, OnInit, inject } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { ProviderApiService } from '../../core/services/provider-api.service';
import {
  AvailabilitySlot,
  LOCATIONS,
  PageResponse,
  SPECIALTIES,
} from '../../core/models/api.models';

function dateRangeValidator(group: AbstractControl): ValidationErrors | null {
  const from = group.get('from')?.value as string | null;
  const to = group.get('to')?.value as string | null;
  if (from && to && to <= from) {
    return { dateRange: true };
  }
  return null;
}

function inputDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

@Component({
  selector: 'app-availability-search',
  imports: [ReactiveFormsModule, DatePipe, RouterLink],
  templateUrl: './availability-search.component.html',
  styleUrl: './availability-search.component.scss',
})
export class AvailabilitySearchComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly providerApi = inject(ProviderApiService);
  private readonly router = inject(Router);

  readonly specialties = SPECIALTIES;
  readonly locations = LOCATIONS;
  readonly pageSize = 10;

  loading = false;
  errorMessage = '';
  slots: AvailabilitySlot[] = [];
  page = 0;
  totalElements = 0;
  totalPages = 0;

  readonly form = this.fb.nonNullable.group(
    {
      specialty: [''],
      location: [''],
      from: [inputDate(1), [Validators.required]],
      to: [inputDate(31), [Validators.required]],
    },
    { validators: dateRangeValidator },
  );

  ngOnInit(): void {
    this.search(0);
  }

  search(page = 0): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { specialty, location, from, to } = this.form.getRawValue();
    this.loading = true;
    this.errorMessage = '';
    this.providerApi
      .searchAvailability({
        specialty,
        location,
        from: `${from}T00:00:00`,
        to: `${to}T23:59:59`,
        page,
        size: this.pageSize,
      })
      .subscribe({
        next: (result: PageResponse<AvailabilitySlot>) => {
          this.loading = false;
          this.slots = result.content;
          this.page = result.page;
          this.totalElements = result.totalElements;
          this.totalPages = result.totalPages;
        },
        error: () => {
          this.loading = false;
          this.errorMessage = 'Availability could not be loaded. Please try again.';
          this.slots = [];
        },
      });
  }

  nextPage(): void {
    if (this.page + 1 < this.totalPages) {
      this.search(this.page + 1);
    }
  }

  previousPage(): void {
    if (this.page > 0) {
      this.search(this.page - 1);
    }
  }

  selectSlot(slot: AvailabilitySlot): void {
    const { from, to } = this.form.getRawValue();
    void this.router.navigate(['/book'], {
      queryParams: {
        slotId: slot.id,
        providerId: slot.providerId,
        providerName: slot.providerName,
        specialty: slot.specialty,
        clinicLocation: slot.clinicLocation,
        startTime: slot.startTime,
        endTime: slot.endTime,
        from,
        to,
      },
    });
  }

  resetFilters(): void {
    this.form.reset({
      specialty: '',
      location: '',
      from: inputDate(1),
      to: inputDate(31),
    });
    this.search(0);
  }

  protected formError(error: string): boolean {
    return this.form.hasError(error) && this.form.controls.to.touched;
  }
}
