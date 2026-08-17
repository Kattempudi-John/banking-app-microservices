import { Component, OnDestroy, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth.service';
import { InputComponent } from '../../shared/input/input.component';
import { ButtonComponent } from '../../shared/button/button.component';
import { AlertBannerComponent } from '../../shared/alert-banner/alert-banner.component';

type LoginStep = 'credentials' | 'twoFactor';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, RouterLink, InputComponent, ButtonComponent, AlertBannerComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent implements OnDestroy {
  readonly step = signal<LoginStep>('credentials');
  readonly username = signal('');
  readonly password = signal('');
  readonly code = signal('');
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  // When the emailed code stops being accepted, as epoch ms. The backend owns the TTL and sends it
  // with the 2FA challenge, so this is derived from the response rather than a hard-coded three
  // minutes. Null means no code is outstanding (we are still on the credentials step).
  readonly codeExpiresAt = signal<number | null>(null);

  // The only piece of clock state we store. Everything the user sees is recomputed from it, so the
  // display is always "expiry minus the current time" rather than a running total that the ticks
  // are responsible for keeping accurate.
  private readonly now = signal(Date.now());

  private countdownHandle: ReturnType<typeof setInterval> | null = null;

  // Recomputed from Date.now() on every tick instead of being decremented. Browsers throttle timers
  // in a background tab to roughly one callback a minute, so a counter that subtracted one per tick
  // would drift far behind real time and keep showing a live countdown for a code the server has
  // already thrown away. Reading the clock means a late tick simply jumps to the right value.
  readonly secondsRemaining = computed(() => {
    const expiresAt = this.codeExpiresAt();
    if (expiresAt === null) {
      return 0;
    }
    return Math.max(0, Math.ceil((expiresAt - this.now()) / 1000));
  });

  // "M:SS" — minutes unpadded, seconds always two digits, so it reads like a clock.
  readonly countdown = computed(() => {
    const remaining = this.secondsRemaining();
    const minutes = Math.floor(remaining / 60);
    const seconds = remaining % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  });

  // Guarded on codeExpiresAt so a screen that never issued a code is not reported as "expired"
  // (secondsRemaining is 0 in both cases).
  readonly codeExpired = computed(() => this.codeExpiresAt() !== null && this.secondsRemaining() === 0);

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {
    if (this.route.snapshot.queryParamMap.get('registered') === 'true') {
      this.successMessage.set('Account created successfully. Please log in.');
    }
  }

  // The interval outlives the component otherwise: navigating away mid-2FA left it ticking against
  // a destroyed view forever.
  ngOnDestroy(): void {
    this.stopCountdown();
  }

  onLoginSubmit(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.authService.login({ username: this.username(), password: this.password() }).subscribe({
      next: (response) => {
        this.loading.set(false);
        if (response.status === 'SUCCESS') {
          this.router.navigate(['/dashboard']);
        } else {
          this.startCountdown(response.expires_in_seconds);
          this.step.set('twoFactor');
        }
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Invalid username or password.');
      },
    });
  }

  onVerifySubmit(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.authService.verifyTwoFa({ code: this.code() }).subscribe({
      next: () => {
        this.loading.set(false);
        // Stop before navigating: once the user is on the dashboard the countdown is meaningless,
        // and the router tears this component down asynchronously.
        this.stopCountdown();
        this.router.navigate(['/dashboard']);
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Invalid verification code. Please try again.');
      },
    });
  }

  onResend(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.authService.resendTwoFaCode().subscribe({
      next: (response) => {
        this.loading.set(false);
        // The old code is dead the moment a new one is issued, so anything already typed would only
        // fail verification.
        this.code.set('');
        this.startCountdown(response.expires_in_seconds);
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Could not send a new code. Please try again.');
      },
    });
  }

  private startCountdown(expiresInSeconds: number): void {
    // Resend calls this again while the previous interval is still running (it only self-clears at
    // zero, and a resend usually happens before that), which would orphan the first handle and
    // leave two timers writing the same signal.
    this.stopCountdown();

    const startedAt = Date.now();
    this.now.set(startedAt);
    this.codeExpiresAt.set(startedAt + expiresInSeconds * 1000);

    // Writing a signal from a plain setInterval is enough to schedule change detection: the app is
    // zoneless, so the signal graph - not zone.js - is what notifies the view.
    this.countdownHandle = setInterval(() => {
      this.now.set(Date.now());
      if (this.secondsRemaining() === 0) {
        // Nothing left to count. The expired state is static, so the timer would just burn a
        // callback a second until the user acted.
        this.stopCountdown();
      }
    }, 1000);
  }

  private stopCountdown(): void {
    if (this.countdownHandle !== null) {
      clearInterval(this.countdownHandle);
      this.countdownHandle = null;
    }
  }
}
