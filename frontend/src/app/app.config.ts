import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { AuthService } from './core/auth.service';
import { jwtInterceptor } from './core/jwt.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([jwtInterceptor])),
    // Ordering is the whole point: an app initializer is awaited BEFORE the router resolves the
    // first route, so by the time authGuard runs the access token has already been rebuilt from
    // the Refresh-Token cookie. Doing this inside the guard instead would work too, but the guard
    // has already been asked about a specific URL by then - and any redirect it issues in the
    // meantime is exactly the "refreshing logs me out and dumps me on /login" behaviour being
    // fixed. Restoring first means the reload simply re-renders the page the user was on.
    provideAppInitializer(() => inject(AuthService).restoreSession()),
  ],
};
