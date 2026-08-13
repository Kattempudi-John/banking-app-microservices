import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.accessToken();
  const isAuthServiceRequest = req.url.startsWith(environment.authApiUrl);

  const authorizedReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      // Deliberately not conditioned on a token being present. It used to be, which meant a 401
      // arriving while the in-memory token was momentarily absent - cleared by an earlier failure,
      // or a request that raced the startup session restore - was passed straight through as an
      // error instead of being retried against the still-valid Refresh-Token cookie. Attempting the
      // refresh costs one request and fails closed: if there is no usable cookie the catch below
      // clears the session, which is where we would have ended up anyway.
      if (!isAuthServiceRequest && error instanceof HttpErrorResponse && error.status === 401) {
        return authService.refresh().pipe(
          switchMap((refreshed) =>
            next(
              req.clone({ setHeaders: { Authorization: `Bearer ${refreshed.access_token}` } }),
            ),
          ),
          catchError((refreshError: unknown) => {
            authService.clearSession();
            return throwError(() => refreshError);
          }),
        );
      }
      return throwError(() => error);
    }),
  );
};
