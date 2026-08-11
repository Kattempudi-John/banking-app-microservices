import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { ProfileService } from '../../core/services/profile.service';
import { AccountService } from '../../core/services/account.service';
import { AuthService } from '../../core/auth.service';
import { KycStatus } from '../../core/models/profile.models';

describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let profileServiceSpy: jasmine.SpyObj<ProfileService>;
  let accountServiceSpy: jasmine.SpyObj<AccountService>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  async function setup(kycStatus: KycStatus = 'PENDING_VERIFICATION'): Promise<void> {
    profileServiceSpy = jasmine.createSpyObj('ProfileService', ['getKycStatus', 'updateContactInfo']);
    profileServiceSpy.getKycStatus.and.returnValue(of(kycStatus));
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts']);
    accountServiceSpy.getAccounts.and.returnValue(of([]));
    authServiceSpy = jasmine.createSpyObj('AuthService', ['logout'], { userId: () => 42 });
    authServiceSpy.logout.and.returnValue(of({}));

    TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profileServiceSpy },
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });

    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function inputById(id: string): HTMLInputElement {
    return fixture.nativeElement.querySelector(`app-input[id="${id}"] input`);
  }

  function setValue(id: string, value: string): void {
    const input = inputById(id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function fillValidForm(): Promise<void> {
    setValue('legalName', 'Jane Q Public');
    setValue('dateOfBirth', '1990-04-17');
    setValue('phoneNumber', '+15551234567');
    setValue('addressLine1', '123 Main St');
    setValue('city', 'Springfield');
    setValue('state', 'IL');
    setValue('zipCode', '62704');
    await fixture.whenStable();
  }

  function clickButtonContaining(text: string): void {
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.includes(text))!.click();
  }

  // The submit button is labelled "Verify my identity" until KYC is APPROVED and "Save" afterwards,
  // so tests that just want to submit the form should not care which of the two it currently reads.
  function submitForm(): void {
    const submit: HTMLButtonElement = fixture.nativeElement.querySelector('button[type="submit"]');
    submit.click();
  }

  it('fetches and displays the KYC status for the logged-in user', async () => {
    await setup('APPROVED');
    expect(profileServiceSpy.getKycStatus).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('APPROVED');
  });

  it('shows an informational note when KYC status is not APPROVED', async () => {
    await setup('PENDING_VERIFICATION');
    expect(fixture.nativeElement.textContent).toContain('has not been verified yet');
  });

  it('does not show the informational note when KYC status is APPROVED', async () => {
    await setup('APPROVED');
    expect(fixture.nativeElement.textContent).not.toContain('has not been verified yet');
  });

  it('submits valid contact info and shows a save confirmation', async () => {
    await setup();
    profileServiceSpy.updateContactInfo.and.returnValue(of('APPROVED' as KycStatus));

    await fillValidForm();
    submitForm();
    fixture.detectChanges();

    expect(profileServiceSpy.updateContactInfo).toHaveBeenCalledWith({
      legalName: 'Jane Q Public',
      dateOfBirth: '1990-04-17',
      phoneNumber: '+15551234567',
      addressLine1: '123 Main St',
      city: 'Springfield',
      state: 'IL',
      zipCode: '62704',
    });
    expect(fixture.nativeElement.textContent).toContain('saved');
  });

  it('confirms verification and flips the status to APPROVED after submitting', async () => {
    await setup('PENDING_VERIFICATION');
    profileServiceSpy.updateContactInfo.and.returnValue(of('APPROVED' as KycStatus));

    await fillValidForm();
    submitForm();
    fixture.detectChanges();

    // The status the backend returned is what the page shows - no second getKycStatus round trip,
    // which is what used to race the commit.
    expect(profileServiceSpy.getKycStatus).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('APPROVED');
    expect(fixture.nativeElement.textContent).toContain('Transfers are now enabled');
    expect(fixture.nativeElement.textContent).not.toContain('has not been verified yet');
  });

  it('rejects a date of birth under 18 without calling the backend', async () => {
    await setup();
    await fillValidForm();

    const underage = new Date();
    underage.setFullYear(underage.getFullYear() - 10);
    setValue('dateOfBirth', underage.toISOString().slice(0, 10));
    await fixture.whenStable();

    submitForm();
    fixture.detectChanges();

    expect(profileServiceSpy.updateContactInfo).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('at least 18 years old');
  });

  it('requires a legal name before submitting', async () => {
    await setup();
    await fillValidForm();
    setValue('legalName', '   ');
    await fixture.whenStable();

    submitForm();
    fixture.detectChanges();

    expect(profileServiceSpy.updateContactInfo).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('full legal name');
  });

  it('shows inline validation and does not submit for an invalid phone number', async () => {
    await setup();
    await fillValidForm();
    setValue('phoneNumber', 'not-a-phone');
    await fixture.whenStable();

    submitForm();
    fixture.detectChanges();

    expect(profileServiceSpy.updateContactInfo).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('phone number');
  });

  it('shows an error message when saving fails', async () => {
    await setup();
    profileServiceSpy.updateContactInfo.and.returnValue(throwError(() => new Error('server error')));

    await fillValidForm();
    submitForm();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Something went wrong');
  });

  it('shows each account\'s full account number, IBAN and SWIFT code in the Receive Money section', async () => {
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts']);
    accountServiceSpy.getAccounts.and.returnValue(
      of([
        {
          accountId: 1,
          accountType: 'CHECKING',
          availableBalance: 100,
          routingNumber: '021000021',
          maskedAccountNumber: '****1234',
          accountNumber: '9876541234',
          iban: 'XB00021000021123456789012',
          swiftCode: 'XBUSUS31',
          status: 'ACTIVE',
        },
      ]),
    );
    profileServiceSpy = jasmine.createSpyObj('ProfileService', ['getKycStatus', 'updateContactInfo']);
    profileServiceSpy.getKycStatus.and.returnValue(of('APPROVED'));
    authServiceSpy = jasmine.createSpyObj('AuthService', ['logout'], { userId: () => 42 });
    authServiceSpy.logout.and.returnValue(of({}));

    TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profileServiceSpy },
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('XB00021000021123456789012');
    expect(fixture.nativeElement.textContent).toContain('XBUSUS31');
    // The unmasked number, not the '****1234' the heading shows - paying another user goes by
    // account number, so the owner has to be able to read the whole thing to share it.
    expect(fixture.nativeElement.textContent).toContain('9876541234');
  });

  it('copies the account number to the clipboard and confirms it', async () => {
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    // navigator.clipboard is undefined in headless Chrome without a user gesture, so it's stubbed
    // rather than driven for real - the assertion is that the component asks for the right value.
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    await setup();

    fixture.componentInstance.copyToClipboard('1-number', '9876541234');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(writeText).toHaveBeenCalledWith('9876541234');
    expect(fixture.componentInstance.copiedField()).toBe('1-number');
  });
});
