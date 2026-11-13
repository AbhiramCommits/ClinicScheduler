import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

function jwt(expiresInSeconds = 3600): string {
  const payload = {
    sub: 'patient@clinic.test',
    exp: Math.floor(Date.now() / 1000) + expiresInSeconds,
  };
  const base64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  return `eyJhbGciOiJIUzI1NiJ9.${padded}.signature`;
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: { navigate: jasmine.Spy };

  beforeEach(() => {
    localStorage.clear();
    router = { navigate: jasmine.createSpy('navigate').and.resolveTo(true) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    // Instantiate the session service before any test seeds localStorage, so its constructor
    // does not treat a token-without-user as a stale session.
    TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('attaches the bearer token to API requests', () => {
    const token = jwt();
    localStorage.setItem('clinic.token', token);

    http.get('/api/appointments').subscribe();

    const request = httpMock.expectOne('/api/appointments');
    expect(request.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    request.flush([]);
  });

  it('does not attach a token when the session is empty', () => {
    http.get('/api/providers').subscribe();

    const request = httpMock.expectOne('/api/providers');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ content: [] });
  });

  it('clears the session and redirects to /login on a protected 401', () => {
    const auth = TestBed.inject(AuthService);
    localStorage.setItem('clinic.token', jwt());
    localStorage.setItem(
      'clinic.user',
      JSON.stringify({
        email: 'patient@clinic.test',
        name: 'Pat',
        role: 'PATIENT',
        patientId: 'patient-1',
      }),
    );

    http.get('/api/appointments').subscribe({ error: () => undefined });

    const request = httpMock.expectOne('/api/appointments');
    request.flush({ error: 'UNAUTHORIZED' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem('clinic.token')).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('passes login failures through without redirecting', () => {
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });

    const request = httpMock.expectOne('/api/auth/login');
    request.flush(
      { error: 'INVALID_CREDENTIALS' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(router.navigate).not.toHaveBeenCalled();
  });
});
