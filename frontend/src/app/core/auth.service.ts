import { Injectable, computed, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of, tap, catchError, throwError } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  LoginRequest,
  LoginResponse,
  RefreshResponse,
  RegisterRequest,
  RegisterResponse,
  VerifyTwoFaRequest,
} from './models/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly baseUrl = environment.authApiUrl;
  private readonly accessTokenSignal = signal<string | null>(null);
  private preAuthToken: string | null = null;

  readonly accessToken = this.accessTokenSignal.asReadonly();
  readonly isLoggedIn = computed(() => this.accessTokenSignal() !== null);
  readonly userId = computed(() => this.decodeUserId(this.accessTokenSignal()));

  constructor(private readonly http: HttpClient) {}

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${this.baseUrl}/login`, request, { withCredentials: true })
      .pipe(
        tap((response) => {
          if (response.status === 'SUCCESS') {
            this.accessTokenSignal.set(response.access_token);
          } else {
            this.preAuthToken = response.pre_auth_token;
          }
        }),
      );
  }

  verifyTwoFa(request: VerifyTwoFaRequest): Observable<LoginResponse> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${this.preAuthToken}` });
    return this.http
      .post<LoginResponse>(`${this.baseUrl}/verify-2fa/sms`, request, {
        headers,
        withCredentials: true,
      })
      .pipe(
        tap((response) => {
          if (response.status === 'SUCCESS') {
            this.preAuthToken = null;
            this.accessTokenSignal.set(response.access_token);
          }
        }),
      );
  }

  refresh(): Observable<RefreshResponse> {
    return this.http
      .post<RefreshResponse>(`${this.baseUrl}/refresh`, null, { withCredentials: true })
      .pipe(tap((response) => this.accessTokenSignal.set(response.access_token)));
  }

  // Run once at startup, before the router resolves any route. The access token lives in memory
  // only, so a browser refresh threw it away and authGuard bounced the user to /login even though
  // their session was still perfectly valid - the Refresh-Token cookie is httpOnly and survives a
  // reload untouched. This trades that cookie back for an access token so the reload lands on the
  // page the user was already looking at.
  //
  // Storing the access token in localStorage would also survive a reload and is the more obvious
  // fix, but it hands the token to any script that manages to run on the page. The cookie is
  // httpOnly precisely so JavaScript cannot read it; re-deriving from it keeps that property.
  //
  // Never fails: someone who is genuinely logged out has no cookie, and their 401 here is the
  // expected answer, not an error. Letting it through would block the app from starting at all.
  restoreSession(): Observable<RefreshResponse | null> {
    return this.refresh().pipe(catchError(() => of(null)));
  }

  logout(): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/logout`, null).pipe(
      tap(() => this.clearSession()),
      catchError((error) => {
        this.clearSession();
        return throwError(() => error);
      }),
    );
  }

  register(request: RegisterRequest): Observable<RegisterResponse> {
    return this.http.post<RegisterResponse>(`${this.baseUrl}/register`, request);
  }

  clearSession(): void {
    this.preAuthToken = null;
    this.accessTokenSignal.set(null);
  }

  private decodeUserId(token: string | null): number | null {
    if (!token) {
      return null;
    }
    try {
      const payloadSegment = token.split('.')[1];
      const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(base64));
      return typeof payload.userId === 'number' ? payload.userId : null;
    } catch {
      return null;
    }
  }
}
