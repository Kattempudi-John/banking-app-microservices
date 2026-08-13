import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { ProfileService } from '../../core/services/profile.service';
import { AccountService } from '../../core/services/account.service';
import { AuthService } from '../../core/auth.service';
import { ContactInfoView, KycStatus } from '../../core/models/profile.models';

// What the endpoint returns for someone who has never submitted the identity form - every field
// null, which is the default for tests that don't care about pre-filling.
const NO_CONTACT_INFO: ContactInfoView = {
  legalName: null,
  dateOfBirth: null,
  phoneNumber: null,
  addressLine1: null,
  addressLine2: null,
  city: null,
  state: null,
  zipCode: null,
};

const CONTACT_INFO_ON_FILE: ContactInfoView = {
  legalName: 'Jane Q Public',
  dateOfBirth: '1990-04-17',
  // E.164, the way the backend stores it - this is the number the account was registered with.
  phoneNumber: '+15712856947',
  addressLine1: '123 Main St',
  addressLine2: 'Apt 4',
  city: 'Springfield',
  state: 'IL',
  zipCode: '62704',
};

describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let profileServiceSpy: jasmine.SpyObj<ProfileService>;
  let accountServiceSpy: jasmine.SpyObj<AccountService>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  async function setup(
    kycStatus: KycStatus = 'PENDING_VERIFICATION',
    contactInfo: Observable<ContactInfoView> = of(NO_CONTACT_INFO),
  ): Promise<void> {
    profileServiceSpy = jasmine.createSpyObj('ProfileService', [
      'getKycStatus',
      'getContactInfo',
      'updateContactInfo',
    ]);
    profileServiceSpy.getKycStatus.and.returnValue(of(kycStatus));
    profileServiceSpy.getContactInfo.and.returnValue(contactInfo);
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

  describe('pre-filling the identity form', () => {
    it('fills every field from the contact info already on file', async () => {
      await setup('APPROVED', of(CONTACT_INFO_ON_FILE));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(profileServiceSpy.getContactInfo).toHaveBeenCalled();
      expect(inputById('legalName').value).toBe('Jane Q Public');
      expect(inputById('dateOfBirth').value).toBe('1990-04-17');
      expect(inputById('phoneNumber').value).toBe('+15712856947');
      expect(inputById('addressLine1').value).toBe('123 Main St');
      expect(inputById('addressLine2').value).toBe('Apt 4');
      expect(inputById('city').value).toBe('Springfield');
      expect(inputById('state').value).toBe('IL');
      expect(inputById('zipCode').value).toBe('62704');
    });

    // The bug this fixes: a blank form meant people retyped their phone number and ended up
    // registering a different one from the one on the account.
    it('submits the pre-filled phone number unchanged when nothing is edited', async () => {
      await setup('APPROVED', of(CONTACT_INFO_ON_FILE));
      profileServiceSpy.updateContactInfo.and.returnValue(of('APPROVED' as KycStatus));
      await fixture.whenStable();

      submitForm();
      fixture.detectChanges();

      expect(profileServiceSpy.updateContactInfo).toHaveBeenCalledWith({
        legalName: 'Jane Q Public',
        dateOfBirth: '1990-04-17',
        phoneNumber: '+15712856947',
        addressLine1: '123 Main St',
        addressLine2: 'Apt 4',
        city: 'Springfield',
        state: 'IL',
        zipCode: '62704',
      });
    });

    it('leaves fields the record has no value for empty', async () => {
      await setup('PENDING_VERIFICATION', of({ ...NO_CONTACT_INFO, legalName: 'Jane Q Public' }));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(inputById('legalName').value).toBe('Jane Q Public');
      expect(inputById('phoneNumber').value).toBe('');
      expect(inputById('city').value).toBe('');
    });

    it('leaves the form empty and still usable when the lookup fails', async () => {
      await setup('PENDING_VERIFICATION', throwError(() => new HttpErrorResponse({ status: 500 })));
      fixture.detectChanges();

      // A failed read must not present as a page error - it would look like verification itself is
      // broken, when the only thing lost is the convenience of a pre-filled form.
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
      expect(inputById('phoneNumber').value).toBe('');

      profileServiceSpy.updateContactInfo.and.returnValue(of('APPROVED' as KycStatus));
      await fillValidForm();
      submitForm();
      fixture.detectChanges();

      expect(profileServiceSpy.updateContactInfo).toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).toContain('saved');
    });
  });

  describe('a phone number already registered to someone else', () => {
    it('shows the reason the backend gave for a 409', async () => {
      await setup();
      profileServiceSpy.updateContactInfo.and.returnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { error: 'That phone number is already registered to another account.' },
            }),
        ),
      );

      await fillValidForm();
      submitForm();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('already registered to another account');
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });

    it('still names the conflict when a 409 arrives without a usable body', async () => {
      await setup();
      profileServiceSpy.updateContactInfo.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );

      await fillValidForm();
      submitForm();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('already registered to another account');
    });
  });

  // profile-service answers 503 when auth-service is unreachable: the submission was not processed
  // at all, which is a different thing from a rejected one and has to invite a retry.
  describe('the verification service cannot be reached', () => {
    it('shows the reason the server gave for a 503', async () => {
      await setup();
      profileServiceSpy.updateContactInfo.and.returnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 503,
              error: {
                error: "We couldn't confirm your identity verification right now. Please try again in a moment.",
                message: "We couldn't confirm your identity verification right now. Please try again in a moment.",
              },
            }),
        ),
      );

      await fillValidForm();
      submitForm();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't confirm your identity verification");
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });

    it('names the unreachable service when a 503 arrives without a body', async () => {
      await setup();
      profileServiceSpy.updateContactInfo.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503 })),
      );

      await fillValidForm();
      submitForm();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't reach the service to confirm this");
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });
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
    profileServiceSpy = jasmine.createSpyObj('ProfileService', [
      'getKycStatus',
      'getContactInfo',
      'updateContactInfo',
    ]);
    profileServiceSpy.getKycStatus.and.returnValue(of('APPROVED'));
    profileServiceSpy.getContactInfo.and.returnValue(of(NO_CONTACT_INFO));
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
