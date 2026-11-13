import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { LoginResponse } from '../models/api.models';

function jwt(expiresInSeconds = 3600): string {
  const payload = {
    sub: 'staff@clinic.test',
    exp: Math.floor(Date.now() / 1000) + expiresInSeconds,
  };
  const base64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  return `eyJhbGciOiJIUzI1NiJ9.${padded}.signature`;
}

function loginResponse(token: string): LoginResponse {
  return {
    token,
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: {
      email: 'staff@clinic.test',
      name: 'Sam Staff',
      role: 'STAFF',
      patientId: null,
    },
  };
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('starts unauthenticated when there is no stored session', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.token()).toBeNull();
    expect(service.role()).toBeNull();
  });

  it('stores the token and user in memory and localStorage after login', () => {
    const token = jwt();
    service.login('staff@clinic.test', 'staff123').subscribe((response) => {
      expect(response.token).toBe(token);
    });

    const request = httpMock.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      email: 'staff@clinic.test',
      password: 'staff123',
    });
    request.flush(loginResponse(token));

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.role()).toBe('STAFF');
    expect(service.token()).toBe(token);
    expect(localStorage.getItem('clinic.token')).toBe(token);
    expect(localStorage.getItem('clinic.user')).toContain('staff@clinic.test');
  });

  it('rejects an expired stored token on startup', () => {
    localStorage.setItem('clinic.token', jwt(-60));
    localStorage.setItem(
      'clinic.user',
      JSON.stringify({ email: 'staff@clinic.test', name: 'Sam', role: 'STAFF', patientId: null }),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fresh = TestBed.inject(AuthService);

    expect(fresh.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem('clinic.token')).toBeNull();
  });

  it('logout clears both stores', () => {
    localStorage.setItem('clinic.token', jwt());
    localStorage.setItem(
      'clinic.user',
      JSON.stringify({ email: 'staff@clinic.test', name: 'Sam', role: 'STAFF', patientId: null }),
    );
    service.logout();

    expect(service.isAuthenticated()).toBeFalse();
    expect(service.user()).toBeNull();
    expect(localStorage.getItem('clinic.token')).toBeNull();
    expect(localStorage.getItem('clinic.user')).toBeNull();
  });
});
