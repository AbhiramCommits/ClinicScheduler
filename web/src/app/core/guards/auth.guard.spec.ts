import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard } from './auth.guard';
import { staffGuard } from './staff.guard';
import { AuthService } from '../services/auth.service';

describe('route guards', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/search' } as RouterStateSnapshot;

  function configure(isAuthenticated: boolean, role: 'STAFF' | 'PATIENT' | null): { createUrlTree: jasmine.Spy } {
    const router = {
      createUrlTree: jasmine
        .createSpy('createUrlTree')
        .and.returnValue({ toString: () => '/redirect' } as UrlTree),
    };
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { isAuthenticated: () => isAuthenticated, role: () => role },
        },
        { provide: Router, useValue: router },
      ],
    });
    return router;
  }

  afterEach(() => TestBed.resetTestingModule());

  it('authGuard allows an authenticated session', () => {
    configure(true, 'STAFF');
    const result = TestBed.runInInjectionContext(() => authGuard(route, state));
    expect(result).toBeTrue();
  });

  it('authGuard redirects an anonymous visitor to /login with a returnUrl', () => {
    const router = configure(false, null);
    const result = TestBed.runInInjectionContext(() => authGuard(route, state));
    expect(result).not.toBeTrue();
    expect(router.createUrlTree).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/search' },
    });
  });

  it('staffGuard allows staff and rejects patients', () => {
    configure(true, 'STAFF');
    expect(TestBed.runInInjectionContext(() => staffGuard(route, state))).toBeTrue();

    TestBed.resetTestingModule();
    const router = configure(true, 'PATIENT');
    const result = TestBed.runInInjectionContext(() => staffGuard(route, state));
    expect(result).not.toBeTrue();
    expect(router.createUrlTree).toHaveBeenCalledWith(['/appointments']);
  });
});
