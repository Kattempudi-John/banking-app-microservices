import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

function makeFakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: Record<string, unknown>) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${base64url({ alg: 'HS256' })}.${base64url(payload)}.signature`;
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('is created with no logged-in user', () => {
    expect(service.isLoggedIn()).toBeFalse();
    expect(service.accessToken()).toBeNull();
  });

  it('stores the access token and reports logged in on SUCCESS login', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();

    const req = httpMock.expectOne(`${environment.authApiUrl}/login`);
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ status: 'SUCCESS', access_token: 'token-abc' });

    expect(service.accessToken()).toBe('token-abc');
    expect(service.isLoggedIn()).toBeTrue();
  });

  it('does not store an access token when 2FA is required', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();

    const req = httpMock.expectOne(`${environment.authApiUrl}/login`);
    req.flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 });

    expect(service.accessToken()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
  });

  it('verifies a 2FA code using the pre-auth token and stores the resulting access token', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock
      .expectOne(`${environment.authApiUrl}/login`)
      .flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 });

    service.verifyTwoFa({ code: '123456' }).subscribe();
    const req = httpMock.expectOne(`${environment.authApiUrl}/verify-2fa/sms`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer pre-auth-xyz');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ status: 'SUCCESS', access_token: 'full-token-123' });

    expect(service.accessToken()).toBe('full-token-123');
    expect(service.isLoggedIn()).toBeTrue();
  });

  // The JWT interceptor only attaches accessToken(), which is null until 2FA completes, so the
  // pre-auth bearer has to be built by hand or this request goes out anonymous and 401s.
  it('requests a fresh 2FA code using the pre-auth token', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock
      .expectOne(`${environment.authApiUrl}/login`)
      .flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 });

    service.resendTwoFaCode().subscribe();
    const req = httpMock.expectOne(`${environment.authApiUrl}/verify-2fa/resend`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe('Bearer pre-auth-xyz');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-2nd', expires_in_seconds: 180 });
  });

  // Resend reissues the pre-auth token so a user who waits out the first code is not left holding a
  // valid new code and a dead session. That only helps if the fresh token replaces the stored one.
  it('restashes the reissued pre-auth token so later requests use it', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock
      .expectOne(`${environment.authApiUrl}/login`)
      .flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 });

    service.resendTwoFaCode().subscribe();
    httpMock
      .expectOne(`${environment.authApiUrl}/verify-2fa/resend`)
      .flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-2nd', expires_in_seconds: 180 });

    service.resendTwoFaCode().subscribe();
    const secondReq = httpMock.expectOne(`${environment.authApiUrl}/verify-2fa/resend`);
    expect(secondReq.request.headers.get('Authorization')).toBe('Bearer pre-auth-2nd');
    secondReq.flush({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-3rd', expires_in_seconds: 180 });
  });

  it('refreshes the access token using the refresh-token cookie', () => {
    service.refresh().subscribe();

    const req = httpMock.expectOne(`${environment.authApiUrl}/refresh`);
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ access_token: 'refreshed-token' });

    expect(service.accessToken()).toBe('refreshed-token');
  });

  // A browser refresh throws away the in-memory access token, so without this the user was sent to
  // /login despite holding a perfectly valid session in the httpOnly Refresh-Token cookie.
  it('restores a session from the refresh-token cookie on startup', () => {
    let completed = false;
    service.restoreSession().subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne(`${environment.authApiUrl}/refresh`);
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ access_token: 'restored-token' });

    expect(service.accessToken()).toBe('restored-token');
    expect(service.isLoggedIn()).toBeTrue();
    expect(completed).toBeTrue();
  });

  // This runs as an app initializer, so an error here would stop the whole application booting.
  // A visitor who was never logged in has no cookie, and their 401 is the expected answer.
  it('completes without error when there is no session to restore, leaving the user logged out', () => {
    let errored = false;
    let completed = false;
    service.restoreSession().subscribe({
      error: () => (errored = true),
      complete: () => (completed = true),
    });

    httpMock
      .expectOne(`${environment.authApiUrl}/refresh`)
      .flush({ error: 'Refresh token missing' }, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBeFalse();
    expect(completed).toBeTrue();
    expect(service.isLoggedIn()).toBeFalse();
  });

  it('clears the access token on logout', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock.expectOne(`${environment.authApiUrl}/login`).flush({ status: 'SUCCESS', access_token: 'token-abc' });

    service.logout().subscribe();
    const req = httpMock.expectOne(`${environment.authApiUrl}/logout`);
    req.flush({});

    expect(service.accessToken()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
  });

  it('clears the access token even if the logout request fails', () => {
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock.expectOne(`${environment.authApiUrl}/login`).flush({ status: 'SUCCESS', access_token: 'token-abc' });

    service.logout().subscribe({ error: () => {} });
    const req = httpMock.expectOne(`${environment.authApiUrl}/logout`);
    req.flush('server error', { status: 500, statusText: 'Server Error' });

    expect(service.accessToken()).toBeNull();
  });

  it('exposes null userId when not logged in', () => {
    expect(service.userId()).toBeNull();
  });

  it('exposes the userId decoded from the access token claims after login', () => {
    const token = makeFakeJwt({ sub: 'jdoe', userId: 42, scope: 'FULL_AUTH' });
    service.login({ username: 'jdoe', password: 'secret123' }).subscribe();
    httpMock.expectOne(`${environment.authApiUrl}/login`).flush({ status: 'SUCCESS', access_token: token });

    expect(service.userId()).toBe(42);
  });

  it('registers a new user without storing an access token', () => {
    let result: unknown;
    service
      .register({ username: 'jdoe', password: 'secret123', phoneNumber: '+15551234567', email: 'jdoe@example.com' })
      .subscribe((res) => (result = res));

    const req = httpMock.expectOne(`${environment.authApiUrl}/register`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      username: 'jdoe',
      password: 'secret123',
      phoneNumber: '+15551234567',
      email: 'jdoe@example.com',
    });
    req.flush({ status: 'SUCCESS', message: 'Account created successfully' });

    expect(result).toEqual({ status: 'SUCCESS', message: 'Account created successfully' });
    expect(service.accessToken()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
  });
});
