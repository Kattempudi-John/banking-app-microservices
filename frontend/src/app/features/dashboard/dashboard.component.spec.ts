import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { DashboardComponent } from './dashboard.component';
import { AccountService } from '../../core/services/account.service';
import { ProfileService } from '../../core/services/profile.service';
import { AccountOverview } from '../../core/models/account.models';
import { AuthService } from '../../core/auth.service';

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let accountServiceSpy: jasmine.SpyObj<AccountService>;
  let profileServiceSpy: jasmine.SpyObj<ProfileService>;
  let router: Router;

  const mockAccounts: AccountOverview[] = [
    {
      accountId: 1,
      accountType: 'CHECKING',
      availableBalance: 1204.55,
      routingNumber: '021000021',
      maskedAccountNumber: '****1234',
      accountNumber: '9876541234',
      iban: 'XB00021000021123456789012',
      swiftCode: 'XBUSUS31',
      status: 'ACTIVE',
    },
    {
      accountId: 2,
      accountType: 'SAVINGS',
      availableBalance: 9003.1,
      routingNumber: '021000021',
      maskedAccountNumber: '****5678',
      accountNumber: '9876545678',
      iban: 'XB00021000021987654321098',
      swiftCode: 'XBUSUS31',
      status: 'FROZEN',
    },
  ];

  beforeEach(() => {
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts', 'depositFunds', 'openAccount']);
    profileServiceSpy = jasmine.createSpyObj('ProfileService', ['getKycStatus']);
    // Approved by default so the existing expectations below see the page in its normal state -
    // the KYC-blocked variants are exercised in their own tests at the bottom.
    profileServiceSpy.getKycStatus.and.returnValue(of('APPROVED'));

    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: ProfileService, useValue: profileServiceSpy },
        {
          provide: AuthService,
          useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }),
        },
      ],
    });

    fixture = TestBed.createComponent(DashboardComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate');
  });

  it('calls AccountService.getAccounts on init', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    fixture.detectChanges();
    expect(accountServiceSpy.getAccounts).toHaveBeenCalled();
  });

  it('renders each account with its type, masked number, status, and balance', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('CHECKING');
    expect(text).toContain('****1234');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('1204.55');
    expect(text).toContain('SAVINGS');
    expect(text).toContain('FROZEN');
  });

  it('navigates to the account transactions page when a row is clicked', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    fixture.detectChanges();

    const firstRow = fixture.nativeElement.querySelector('tbody tr');
    firstRow.click();

    expect(router.navigate).toHaveBeenCalledWith(['/accounts', 1, 'transactions']);
  });

  it('shows an error state with a retry option when the accounts request fails', () => {
    accountServiceSpy.getAccounts.and.returnValue(throwError(() => new Error('network error')));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Unable to load your accounts');
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
  });

  it('retries loading accounts when the retry button is clicked', () => {
    accountServiceSpy.getAccounts.and.returnValue(throwError(() => new Error('network error')));
    fixture.detectChanges();

    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.includes('Retry'))!.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('CHECKING');
  });

  function buttonLabels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('button')).map((b) =>
      (b as HTMLButtonElement).textContent!.trim(),
    );
  }

  // account-service refuses both of these with a 403 until KYC is approved, so offering them to an
  // unverified user would only produce a rejected form
  it('hides Add Funds and Open New Account while KYC is not approved', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    profileServiceSpy.getKycStatus.and.returnValue(of('PENDING_VERIFICATION'));

    fixture.detectChanges();

    expect(buttonLabels()).not.toContain('Add Funds');
    expect(buttonLabels()).not.toContain('Open New Account');
    expect(fixture.nativeElement.textContent).toContain('Verify your identity to unlock these actions');
    expect(fixture.nativeElement.textContent).toContain('verified identity');
    // the backend's status value is not customer-facing copy
    expect(fixture.nativeElement.textContent).not.toContain('PENDING_VERIFICATION');
  });

  it('shows Add Funds and Open New Account once KYC is approved', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    profileServiceSpy.getKycStatus.and.returnValue(of('APPROVED'));

    fixture.detectChanges();

    expect(buttonLabels()).toContain('Add Funds');
    expect(buttonLabels()).toContain('Open New Account');
  });

  // a failed status lookup must not be treated as approval
  it('keeps both actions hidden when the KYC status cannot be loaded', () => {
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    profileServiceSpy.getKycStatus.and.returnValue(throwError(() => new Error('network error')));

    fixture.detectChanges();

    expect(buttonLabels()).not.toContain('Add Funds');
    expect(buttonLabels()).not.toContain('Open New Account');
  });

  // account-service answers 503 when it cannot reach the service that says whether the user is
  // verified. Nothing was charged and no account was created, so neither message may blame the
  // user's input for it.
  describe('account-service cannot confirm the verification status', () => {
    const UNAVAILABLE_BODY = {
      error: "We couldn't confirm your identity verification right now. Please try again in a moment.",
      message: "We couldn't confirm your identity verification right now. Please try again in a moment.",
    };

    function clickButtonContaining(text: string): void {
      const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
      buttons.find((b) => b.textContent?.includes(text))!.click();
    }

    async function openDepositForm(): Promise<void> {
      accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
      fixture.detectChanges();

      clickButtonContaining('Add Funds');
      fixture.detectChanges();
      // The amount field only binds once NgForm has registered the control it lives in, which it
      // does in a microtask - typing before that lands leaves the amount empty.
      await fixture.whenStable();

      const input: HTMLInputElement = fixture.nativeElement.querySelector('app-input[id="depositAmount"] input');
      input.value = '50';
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    }

    it('shows the reason the server gave for a 503 on a deposit', async () => {
      await openDepositForm();
      accountServiceSpy.depositFunds.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503, error: UNAVAILABLE_BODY })),
      );

      clickButtonContaining('Deposit');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't confirm your identity verification");
      expect(fixture.nativeElement.textContent).not.toContain('amount is invalid');
    });

    // A gateway can strip the body off a 503, which used to leave the deposit blaming the amount.
    it('names the unreachable service when a deposit 503s without a body', async () => {
      await openDepositForm();
      accountServiceSpy.depositFunds.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503 })),
      );

      clickButtonContaining('Deposit');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't reach the service to confirm this");
      expect(fixture.nativeElement.textContent).not.toContain('amount is invalid');
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });

    it('shows the reason the server gave for a 503 on opening an account', () => {
      accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
      accountServiceSpy.openAccount.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503, error: UNAVAILABLE_BODY })),
      );
      fixture.detectChanges();

      clickButtonContaining('Open New Account');
      fixture.detectChanges();
      clickButtonContaining('Open Account');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't confirm your identity verification");
    });

    it('names the unreachable service when opening an account 503s without a body', () => {
      accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
      accountServiceSpy.openAccount.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503 })),
      );
      fixture.detectChanges();

      clickButtonContaining('Open New Account');
      fixture.detectChanges();
      clickButtonContaining('Open Account');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't reach the service to confirm this");
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });
  });
});
