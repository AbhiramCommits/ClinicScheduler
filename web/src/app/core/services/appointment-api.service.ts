import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Appointment, BookingRequest } from '../models/api.models';

@Injectable({ providedIn: 'root' })
export class AppointmentApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.appointmentApiBaseUrl;

  book(request: BookingRequest): Observable<Appointment> {
    return this.http.post<Appointment>(`${this.baseUrl}/api/appointments`, request);
  }

  list(patientId?: string): Observable<Appointment[]> {
    let params = new HttpParams();
    if (patientId) {
      params = params.set('patientId', patientId);
    }
    return this.http.get<Appointment[]>(`${this.baseUrl}/api/appointments`, { params });
  }

  get(id: number): Observable<Appointment> {
    return this.http.get<Appointment>(`${this.baseUrl}/api/appointments/${id}`);
  }

  cancel(id: number): Observable<Appointment> {
    return this.http.delete<Appointment>(`${this.baseUrl}/api/appointments/${id}`);
  }
}
