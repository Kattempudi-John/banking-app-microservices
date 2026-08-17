import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { AuthService } from '../../core/auth.service';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  function setup(queryParams: Record<string, string> = {}): void {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['login', 'verifyTwoFa', 'resendTwoFaCode']);

    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });

    fixture = TestBed.createComponent(LoginComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    fixture.detectChanges();
  }

  beforeEach(async () => {
    setup();
    await fixture.whenStable();
  });

  function usernameInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('app-input[id="username"] input');
  }

  function passwordInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('app-input[id="password"] input');
  }

  function codeInput(): HTMLInputElement | null {
    return fixture.nativeElement.querySelector('app-input[id="code"] input');
  }

  function submitForm(): void {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
  }

  function typeInto(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  // The 2FA step renders two buttons once the code lapses (Verify and the resend), so a bare
  // querySelector('button') would silently pick the wrong one. Match on what the user reads.
  function buttonLabelled(pattern: RegExp): HTMLButtonElement | null {
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    return buttons.find((button) => pattern.test(button.textContent ?? '')) ?? null;
  }

  function resendButton(): HTMLButtonElement | null {
    return buttonLabelled(/resend|new code/i);
  }


  it('renders the credentials form and no 2FA code input initially', () => {
    expect(usernameInput()).not.toBeNull();
    expect(passwordInput()).not.toBeNull();
    expect(codeInput()).toBeNull();
  });

  it('calls AuthService.login with the entered credentials on submit', () => {
    authServiceSpy.login.and.returnValue(of({ status: 'SUCCESS', access_token: 'token-abc' }));

    typeInto(usernameInput(), 'jdoe');
    typeInto(passwordInput(), 'secret123');
    submitForm();

    expect(authServiceSpy.login).toHaveBeenCalledWith({ username: 'jdoe', password: 'secret123' });
  });

  it('navigates to /dashboard when login succeeds', () => {
    authServiceSpy.login.and.returnValue(of({ status: 'SUCCESS', access_token: 'token-abc' }));

    typeInto(usernameInput(), 'jdoe');
    typeInto(passwordInput(), 'secret123');
    submitForm();

    expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
  });

  it('shows the 2FA code step when login responds with 2FA_REQUIRED', () => {
    authServiceSpy.login.and.returnValue(
      of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 }),
    );

    typeInto(usernameInput(), 'jdoe');
    typeInto(passwordInput(), 'secret123');
    submitForm();
    fixture.detectChanges();

    expect(codeInput()).not.toBeNull();
    expect(usernameInput()).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('shows an error message when login fails', () => {
    authServiceSpy.login.and.returnValue(throwError(() => new Error('unauthorized')));

    typeInto(usernameInput(), 'jdoe');
    typeInto(passwordInput(), 'wrong');
    submitForm();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Invalid username or password');
  });

  describe('2FA verification step', () => {
    beforeEach(async () => {
      authServiceSpy.login.and.returnValue(
        of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 }),
      );
      typeInto(usernameInput(), 'jdoe');
      typeInto(passwordInput(), 'secret123');
      submitForm();
      fixture.detectChanges();
      await fixture.whenStable();
    });

    it('tells the user the code was emailed', () => {
      // Codes moved from SMS to email. An emailed code does not land on the device already in the
      // user's hand, so the screen has to say where to go and look for it - without this the step
      // named no delivery channel at all.
      expect(fixture.nativeElement.textContent).toContain(
        'We emailed a verification code to the address on your account.',
      );
    });

    it('calls AuthService.verifyTwoFa with the entered code on submit', () => {
      authServiceSpy.verifyTwoFa.and.returnValue(of({ status: 'SUCCESS', access_token: 'full-token' }));

      typeInto(codeInput()!, '123456');
      submitForm();

      expect(authServiceSpy.verifyTwoFa).toHaveBeenCalledWith({ code: '123456' });
    });

    it('navigates to /dashboard when 2FA verification succeeds', () => {
      authServiceSpy.verifyTwoFa.and.returnValue(of({ status: 'SUCCESS', access_token: 'full-token' }));

      typeInto(codeInput()!, '123456');
      submitForm();

      expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
    });

    it('shows an error message when 2FA verification fails', () => {
      authServiceSpy.verifyTwoFa.and.returnValue(throwError(() => new Error('invalid code')));

      typeInto(codeInput()!, '000000');
      submitForm();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Invalid verification code');
    });
  });

  describe('2FA code countdown and resend', () => {
    // No zone.js in this app, so fakeAsync/tick do not exist here. Jasmine's own clock is the
    // substitute, and mockDate is not optional with it: the countdown is derived from Date.now()
    // on every tick (a decrementing counter would run slow in a throttled background tab), so
    // without a mocked Date the interval fires and the display never moves.
    const CLOCK_START = new Date('2026-08-17T09:00:00Z');

    let startedIntervals: number[];
    let clearedIntervals: number[];
    let mockedSetInterval: typeof window.setInterval;
    let mockedClearInterval: typeof window.clearInterval;

    function advance(milliseconds: number): void {
      jasmine.clock().tick(milliseconds);
      fixture.detectChanges();
    }

    // ngModel pushes a programmatic value change to the value accessor in a microtask, so a code
    // the component clears is only on screen a turn later. Nothing here waits on a timer, which
    // matters while the clock is mocked.
    async function settle(): Promise<void> {
      fixture.detectChanges();
      for (let i = 0; i < 3; i++) {
        await Promise.resolve();
      }
      fixture.detectChanges();
    }

    beforeEach(() => {
      jasmine.clock().install();
      jasmine.clock().mockDate(CLOCK_START);

      // Wrap the mocked timer functions (install() has already replaced them) so the teardown
      // test can prove every interval the component started was also stopped.
      startedIntervals = [];
      clearedIntervals = [];
      mockedSetInterval = window.setInterval;
      mockedClearInterval = window.clearInterval;
      window.setInterval = ((handler: TimerHandler, timeout?: number, ...args: unknown[]) => {
        const id = mockedSetInterval(handler, timeout, ...args);
        startedIntervals.push(id);
        return id;
      }) as typeof window.setInterval;
      window.clearInterval = ((id?: number) => {
        if (id !== undefined) {
          clearedIntervals.push(id);
        }
        mockedClearInterval(id);
      }) as typeof window.clearInterval;

      authServiceSpy.login.and.returnValue(
        of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-xyz', expires_in_seconds: 180 }),
      );
      typeInto(usernameInput(), 'jdoe');
      typeInto(passwordInput(), 'secret123');
      submitForm();
      fixture.detectChanges();
    });

    afterEach(() => {
      // Restore in this order: the wrappers first, so uninstall() puts the real timers back over
      // the mocked ones rather than leaving a dead mock behind for the next spec.
      window.setInterval = mockedSetInterval;
      window.clearInterval = mockedClearInterval;
      jasmine.clock().uninstall();
    });

    it('shows the full lifetime of the code as soon as the step opens', () => {
      expect(fixture.nativeElement.textContent).toContain('Expires in 3:00');
    });

    it('counts the code down as time passes', () => {
      advance(1000);
      expect(fixture.nativeElement.textContent).toContain('Expires in 2:59');

      advance(60000);
      expect(fixture.nativeElement.textContent).toContain('Expires in 1:59');
    });

    it('pads the seconds to two digits below ten', () => {
      // 0:9 instead of 0:09 is the classic M:SS bug, and it only shows up in the last nine
      // seconds of the code's life - exactly when the user is watching the number.
      advance(171000);
      expect(fixture.nativeElement.textContent).toContain('Expires in 0:09');
    });

    it('offers no resend while the code is still live', () => {
      expect(resendButton()).toBeNull();

      advance(179000);
      expect(resendButton()).toBeNull();
    });

    it('offers a resend once the countdown reaches zero', () => {
      advance(180000);

      expect(fixture.nativeElement.textContent).toContain('That code has expired');
      expect(fixture.nativeElement.textContent).not.toContain('Expires in');
      expect(resendButton()).not.toBeNull();
    });

    it('asks the service for a new code when the resend is clicked', () => {
      authServiceSpy.resendTwoFaCode.and.returnValue(
        of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-second', expires_in_seconds: 180 }),
      );

      advance(180000);
      resendButton()!.click();

      expect(authServiceSpy.resendTwoFaCode).toHaveBeenCalled();
    });

    it('restarts the countdown and hides the resend after a new code is sent', () => {
      authServiceSpy.resendTwoFaCode.and.returnValue(
        of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-second', expires_in_seconds: 180 }),
      );

      advance(180000);
      resendButton()!.click();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Expires in 3:00');
      expect(resendButton()).toBeNull();
    });

    it('clears the code already typed when a new one is sent', async () => {
      authServiceSpy.resendTwoFaCode.and.returnValue(
        of({ status: '2FA_REQUIRED', pre_auth_token: 'pre-auth-second', expires_in_seconds: 180 }),
      );

      typeInto(codeInput()!, '123456');
      advance(180000);
      resendButton()!.click();
      await settle();

      // Leaving the dead code in the box invites the user to submit it against the new one.
      expect(codeInput()!.value).toBe('');
    });

    it('shows an error when the resend fails', () => {
      authServiceSpy.resendTwoFaCode.and.returnValue(throwError(() => new Error('rate limited')));

      advance(180000);
      resendButton()!.click();
      fixture.detectChanges();

      // A silent failure here is the worst outcome: the user clicks, nothing visibly happens, and
      // they sit waiting for an email that was never sent.
      expect(fixture.nativeElement.textContent).toContain('Could not send a new code');
    });

    it('stops the countdown interval when the component is destroyed', () => {
      advance(1000);
      expect(startedIntervals.length).toBeGreaterThan(0);

      fixture.destroy();

      const leaked = startedIntervals.filter((id) => !clearedIntervals.includes(id));
      expect(leaked).toEqual([]);
    });
  });

  it('renders a link to /signup', () => {
    const links: HTMLAnchorElement[] = Array.from(fixture.nativeElement.querySelectorAll('a'));
    expect(links.some((a) => a.getAttribute('href') === '/signup')).toBeTrue();
  });

  describe('after registering (redirected with ?registered=true)', () => {
    beforeEach(async () => {
      TestBed.resetTestingModule();
      setup({ registered: 'true' });
      await fixture.whenStable();
    });

    it('shows a success banner prompting the user to log in', () => {
      expect(fixture.nativeElement.textContent).toContain('Account created');
    });
  });
});
