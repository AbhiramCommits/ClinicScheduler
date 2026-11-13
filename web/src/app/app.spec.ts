import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { App } from './app';

@Component({ template: '' })
class EmptyComponent {}

describe('App', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideRouter([
          { path: 'search', component: EmptyComponent },
          { path: 'appointments', component: EmptyComponent },
          { path: 'staff/appointments', component: EmptyComponent },
          { path: 'login', component: EmptyComponent },
        ]),
      ],
    }).compileComponents();
  });

  it('creates the app shell and shows the brand', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ClinicScheduler');
    expect(text).toContain('Sign in');
  });
});
