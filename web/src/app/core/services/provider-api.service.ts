import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AvailabilitySlot,
  PageResponse,
  Provider,
  SearchAvailabilityParams,
} from '../models/api.models';

@Injectable({ providedIn: 'root' })
export class ProviderApiService {
  private readonly baseUrl = environment.providerApiBaseUrl;

  constructor(private readonly http: HttpClient) {}

  searchAvailability(params: SearchAvailabilityParams): Observable<PageResponse<AvailabilitySlot>> {
    let httpParams = new HttpParams()
      .set('from', params.from)
      .set('to', params.to)
      .set('page', params.page)
      .set('size', params.size);
    if (params.specialty) {
      httpParams = httpParams.set('specialty', params.specialty);
    }
    if (params.location) {
      httpParams = httpParams.set('location', params.location);
    }
    return this.http.get<PageResponse<AvailabilitySlot>>(
      `${this.baseUrl}/api/availability/search`,
      { params: httpParams },
    );
  }

  listProviders(specialty?: string, location?: string, page = 0, size = 20): Observable<PageResponse<Provider>> {
    let httpParams = new HttpParams().set('page', page).set('size', size);
    if (specialty) {
      httpParams = httpParams.set('specialty', specialty);
    }
    if (location) {
      httpParams = httpParams.set('location', location);
    }
    return this.http.get<PageResponse<Provider>>(`${this.baseUrl}/api/providers`, {
      params: httpParams,
    });
  }

  getProvider(id: number): Observable<Provider> {
    return this.http.get<Provider>(`${this.baseUrl}/api/providers/${id}`);
  }

  getProviderAvailability(providerId: number, from: string, to: string): Observable<AvailabilitySlot[]> {
    const httpParams = new HttpParams().set('from', from).set('to', to);
    return this.http.get<AvailabilitySlot[]>(
      `${this.baseUrl}/api/providers/${providerId}/availability`,
      { params: httpParams },
    );
  }
}
