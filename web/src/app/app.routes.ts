import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { staffGuard } from './core/guards/staff.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'search',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/availability-search/availability-search.component').then(
        (m) => m.AvailabilitySearchComponent,
      ),
  },
  {
    path: 'book',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/booking-form/booking-form.component').then((m) => m.BookingFormComponent),
  },
  {
    path: 'appointments',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/appointment-list/appointment-list.component').then(
        (m) => m.AppointmentListComponent,
      ),
  },
  {
    path: 'staff/appointments',
    canActivate: [authGuard, staffGuard],
    loadComponent: () =>
      import('./features/appointment-list/appointment-list.component').then(
        (m) => m.AppointmentListComponent,
      ),
  },
  { path: '', pathMatch: 'full', redirectTo: 'search' },
  { path: '**', redirectTo: 'search' },
];
