import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthUser, LoginRequest, LoginResponse, Role } from '../models/api.models';

const TOKEN_KEY = 'clinic.token';
const USER_KEY = 'clinic.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly userSubject = new BehaviorSubject<AuthUser | null>(this.restoreUser());
  readonly user$ = this.userSubject.asObservable();

  login(email: string, password: string): Observable<LoginResponse> {
    const request: LoginRequest = { email, password };
    return this.http
      .post<LoginResponse>(`${environment.appointmentApiBaseUrl}/api/auth/login`, request)
      .pipe(
        tap((response) => {
          localStorage.setItem(TOKEN_KEY, response.token);
          localStorage.setItem(USER_KEY, JSON.stringify(response.user));
          this.userSubject.next(response.user);
        }),
      );
  }

  token(): string | null {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token || this.isExpired(token)) {
      return null;
    }
    return token;
  }

  isAuthenticated(): boolean {
    return this.token() !== null;
  }

  role(): Role | null {
    return this.userSubject.value?.role ?? null;
  }

  patientId(): string | null {
    return this.userSubject.value?.patientId ?? null;
  }

  user(): AuthUser | null {
    return this.userSubject.value;
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.userSubject.next(null);
  }

  private restoreUser(): AuthUser | null {
    const rawToken = localStorage.getItem(TOKEN_KEY);
    const rawUser = localStorage.getItem(USER_KEY);
    if (!rawToken || !rawUser || this.isExpired(rawToken)) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
      return null;
    }
    try {
      return JSON.parse(rawUser) as AuthUser;
    } catch {
      return null;
    }
  }

  private isExpired(token: string): boolean {
    const expiry = this.expiryMillis(token);
    return expiry !== null && expiry <= Date.now();
  }

  private expiryMillis(token: string): number | null {
    try {
      const payload = token.split('.')[1];
      if (!payload) {
        return null;
      }
      const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
      const decoded = JSON.parse(atob(normalized)) as { exp?: unknown };
      return typeof decoded.exp === 'number' ? decoded.exp * 1000 : null;
    } catch {
      return null;
    }
  }
}
