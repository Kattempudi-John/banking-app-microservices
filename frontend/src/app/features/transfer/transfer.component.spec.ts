import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { TransferComponent } from './transfer.component';
import { TransferService } from '../../core/services/transfer.service';
import { AccountService } from '../../core/services/account.service';
import { AccountOverview } from '../../core/models/account.models';
import { RecipientPreview } from '../../core/models/transfer.models';
import { AuthService } from '../../core/auth.service';

describe('TransferComponent', () => {
  let fixture: ComponentFixture<TransferComponent>;
  let transferServiceSpy: jasmine.SpyObj<TransferService>;
  let accountServiceSpy: jasmine.SpyObj<AccountService>;

  const mockAccounts: AccountOverview[] = [
    { accountId: 1, accountType: 'CHECKING', availableBalance: 1000, routingNumber: '021000021', maskedAccountNumber: '****1234', accountNumber: '9876541234', iban: 'XB00021000021123456789012', swiftCode: 'XBUSUS31', status: 'ACTIVE' },
    { accountId: 2, accountType: 'SAVINGS', availableBalance: 5000, routingNumber: '021000021', maskedAccountNumber: '****5678', accountNumber: '9876545678', iban: 'XB00021000021987654321098', swiftCode: 'XBUSUS31', status: 'ACTIVE' },
  ];

  beforeEach(async () => {
    transferServiceSpy = jasmine.createSpyObj('TransferService', [
      'transferInternal',
      'transferExternal',
      'transferToRecipient',
      'previewRecipient',
    ]);
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts']);
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));

    TestBed.configureTestingModule({
      imports: [TransferComponent],
      providers: [
        provideRouter([]),
        { provide: TransferService, useValue: transferServiceSpy },
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }) },
      ],
    });

    fixture = TestBed.createComponent(TransferComponent);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  function selects(): HTMLSelectElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('select'));
  }

  function inputById(id: string): HTMLInputElement {
    return fixture.nativeElement.querySelector(`app-input[id="${id}"] input`);
  }

  function setValue(id: string, value: string): void {
    const input = inputById(id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function clickButtonContaining(text: string): void {
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.includes(text))!.click();
  }

  it('populates the account dropdown(s) from AccountService', () => {
    expect(accountServiceSpy.getAccounts).toHaveBeenCalled();
    const options = fixture.nativeElement.querySelectorAll('option');
    expect(Array.from(options).some((o) => (o as HTMLOptionElement).textContent?.includes('****1234'))).toBeTrue();
  });

  describe('internal transfer (default tab)', () => {
    it('submits with the selected accounts and amount', async () => {
      transferServiceSpy.transferInternal.and.returnValue(of({ transactionId: 'txn-1', status: 'COMPLETED', onUsTransfer: true }));

      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '100');
      await fixture.whenStable();

      clickButtonContaining('Send');

      expect(transferServiceSpy.transferInternal).toHaveBeenCalledWith({
        fromAccountId: 1,
        toAccountId: 2,
        amount: 100,
      });
    });

    it('shows a validation error and does not call the service for a non-positive amount', async () => {
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '0');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(transferServiceSpy.transferInternal).not.toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).toContain('positive amount');
    });

    it('shows a success message with the transaction id when the transfer completes', async () => {
      transferServiceSpy.transferInternal.and.returnValue(of({ transactionId: 'txn-1', status: 'COMPLETED', onUsTransfer: true }));
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '100');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('txn-1');
      expect(fixture.nativeElement.textContent.toLowerCase()).toContain('success');
    });

    it('shows an "under review" message when the transfer is PENDING_APPROVAL', async () => {
      transferServiceSpy.transferInternal.and.returnValue(of({ transactionId: 'txn-2', status: 'PENDING_APPROVAL', onUsTransfer: true }));
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '9999');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('under review');
    });

    it('shows the server reason on a 403 KYC error', async () => {
      transferServiceSpy.transferInternal.and.returnValue(
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { error: 'Transfers are disabled until your identity is verified. Check your Profile page for your verification status.' },
        })),
      );
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '100');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Transfers are disabled until your identity is verified');
    });

    // transaction-service answers 503 when it cannot reach the service that confirms identity
    // verification. No money moved, so this has to read as "try again", not as a rejected transfer.
    it('shows the reason the server gave for a 503', async () => {
      transferServiceSpy.transferInternal.and.returnValue(
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
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '100');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't confirm your identity verification");
    });

    it('names the unreachable service when a 503 arrives without a body', async () => {
      transferServiceSpy.transferInternal.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 503 })),
      );
      const [fromSelect, toSelect] = selects();
      fromSelect.value = '1';
      fromSelect.dispatchEvent(new Event('change'));
      toSelect.value = '2';
      toSelect.dispatchEvent(new Event('change'));
      setValue('amount', '100');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("couldn't reach the service to confirm this");
      expect(fixture.nativeElement.textContent).not.toContain('Something went wrong');
    });
  });

  describe('paying another user', () => {
    const VERIFIED: RecipientPreview = {
      maskedAccountNumber: '****5678',
      accountType: 'CHECKING',
      displayName: 'John Smith',
      verified: true,
    };

    function submitButton(): HTMLButtonElement {
      return fixture.nativeElement.querySelector('button[type="submit"]');
    }

    async function lookUp(preview: RecipientPreview): Promise<void> {
      transferServiceSpy.previewRecipient.and.returnValue(of(preview));
      clickButtonContaining('To Someone Else');
      fixture.detectChanges();
      await fixture.whenStable();

      setValue('recipientAccountNumber', '9876545678');
      await fixture.whenStable();

      clickButtonContaining('Look Up');
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    it('warns and blocks Send when the recipient is not verified', async () => {
      await lookUp({ ...VERIFIED, verified: false });

      expect(fixture.nativeElement.textContent).toContain('John Smith');
      expect(fixture.nativeElement.textContent).toContain("identity isn't verified");
      expect(submitButton().disabled).toBeTrue();
    });

    it('shows no warning and leaves Send enabled for a verified recipient', async () => {
      await lookUp(VERIFIED);

      expect(fixture.nativeElement.textContent).toContain('John Smith');
      expect(fixture.nativeElement.textContent).not.toContain("identity isn't verified");
      expect(submitButton().disabled).toBeFalse();
    });

    it('clears the warning once the account number is edited again', async () => {
      await lookUp({ ...VERIFIED, verified: false });

      setValue('recipientAccountNumber', '9876541234');
      await fixture.whenStable();
      fixture.detectChanges();

      // Editing drops the whole preview, so the verdict goes with it rather than lingering next to
      // an account it no longer describes.
      expect(fixture.nativeElement.textContent).not.toContain("identity isn't verified");
      expect(submitButton().disabled).toBeFalse();
    });

    it('sends to a verified recipient', async () => {
      transferServiceSpy.transferToRecipient.and.returnValue(
        of({ transactionId: 'txn-4', status: 'COMPLETED', onUsTransfer: true }),
      );
      await lookUp(VERIFIED);

      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('amount', '25');
      await fixture.whenStable();

      clickButtonContaining('Send');

      expect(transferServiceSpy.transferToRecipient).toHaveBeenCalledWith({
        fromAccountId: 1,
        recipientAccountNumber: '9876545678',
        amount: 25,
      });
    });

    // The warning is only an earlier signal - the backend's 403 is still the gate, and its message
    // has to keep coming through if a recipient's status changes between lookup and send.
    // Critically it must be the RECIPIENT's reason that survives: this page used to replace every
    // 403 with one line about the sender's own identity, so paying an unverified recipient told the
    // sender to go and verify themselves when they already were.
    it('shows the recipient reason, not the sender one, on a 403 from the send itself', async () => {
      transferServiceSpy.transferToRecipient.and.returnValue(
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { error: "This recipient can't receive transfers yet - their identity verification isn't complete." },
        })),
      );
      await lookUp(VERIFIED);

      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('amount', '25');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      const text = fixture.nativeElement.textContent;
      expect(text).toContain("This recipient can't receive transfers yet");
      // The old hardcoded sender line must not reappear - it is the whole bug.
      expect(text).not.toContain('Please verify your identity to enable transfers');
    });

    // Pressing Send before looking anyone up leaves a "look them up first" complaint on screen. It
    // used to survive the lookup that resolved it, so a confirmed recipient sat directly above a
    // message saying they had not been confirmed.
    it('clears the look-up-first complaint once the lookup succeeds', async () => {
      clickButtonContaining('To Someone Else');
      fixture.detectChanges();
      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('amount', '25');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Look up the recipient account number first');

      await lookUp(VERIFIED);
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).not.toContain('Look up the recipient account number first');
    });

    it('requires a lookup before sending to a recipient', async () => {
      clickButtonContaining('To Someone Else');
      fixture.detectChanges();
      await fixture.whenStable();

      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('amount', '25');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(transferServiceSpy.transferToRecipient).not.toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).toContain('Look up the recipient');
    });
  });

  describe('external wire tab', () => {
    beforeEach(async () => {
      clickButtonContaining('External Wire');
      fixture.detectChanges();
      await fixture.whenStable();
    });

    it('shows external wire fields and hides the internal to-account select', () => {
      expect(inputById('iban')).not.toBeNull();
      expect(inputById('swiftCode')).not.toBeNull();
      expect(inputById('beneficiaryName')).not.toBeNull();
    });

    it('submits with the entered wire details', async () => {
      transferServiceSpy.transferExternal.and.returnValue(of({ transactionId: 'txn-3', status: 'COMPLETED', onUsTransfer: false }));

      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('iban', 'GB29NWBK60161331926819');
      setValue('swiftCode', 'NWBKGB2L');
      setValue('beneficiaryName', 'Jane Doe');
      setValue('extAmount', '250');
      await fixture.whenStable();

      clickButtonContaining('Send');

      expect(transferServiceSpy.transferExternal).toHaveBeenCalledWith(1, {
        iban: 'GB29NWBK60161331926819',
        swiftCode: 'NWBKGB2L',
        beneficiaryName: 'Jane Doe',
        amount: 250,
      });
    });

    it('shows a validation error and does not call the service for an invalid IBAN', async () => {
      selects()[0].value = '1';
      selects()[0].dispatchEvent(new Event('change'));
      setValue('iban', 'NOT-AN-IBAN');
      setValue('swiftCode', 'NWBKGB2L');
      setValue('beneficiaryName', 'Jane Doe');
      setValue('extAmount', '250');
      await fixture.whenStable();

      clickButtonContaining('Send');
      fixture.detectChanges();

      expect(transferServiceSpy.transferExternal).not.toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).toContain('IBAN');
    });
  });
});
