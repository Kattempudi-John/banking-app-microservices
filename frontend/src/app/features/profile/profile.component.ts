import { Component, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { extractApiError } from '../../core/api-error';
import { ProfileService } from '../../core/services/profile.service';
import { AccountService } from '../../core/services/account.service';
import { ContactInfoView, KycStatus } from '../../core/models/profile.models';
import { AccountOverview } from '../../core/models/account.models';
import { ButtonComponent } from '../../shared/button/button.component';
import { AlertBannerComponent } from '../../shared/alert-banner/alert-banner.component';
import { NavComponent } from '../../shared/nav/nav.component';
import { InputComponent } from '../../shared/input/input.component';

// Permissive on purpose: the backend normalizes whatever is typed into E.164 before storing it, so
// "(571) 285-6947" and "+1 571 285 6947" are both fine here. This only catches input that plainly
// isn't a phone number; profile-service returns a specific message if it can't resolve one.
const PHONE_PATTERN = /^[+()\-.\s0-9]{7,20}$/;

// Matched to profile-service's own rule (ProfileManagementService.MINIMUM_AGE_YEARS). Checked here
// too so the user is told before a round trip, not because the client is trusted - the backend
// rejects an underage date regardless of what this does.
const MINIMUM_AGE_YEARS = 18;

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, ButtonComponent, AlertBannerComponent, NavComponent, InputComponent],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent implements OnInit {
  readonly kycStatus = signal<KycStatus | null>(null);
  // Without these two the template interpolated a null straight into "KYC Status:", so a slow or
  // failed lookup was indistinguishable from a user who genuinely has no status - it just rendered
  // a bare label forever.
  readonly kycLoading = signal(true);
  readonly kycError = signal(false);

  readonly legalName = signal('');
  readonly dateOfBirth = signal('');
  readonly phoneNumber = signal('');
  readonly addressLine1 = signal('');
  readonly addressLine2 = signal('');
  readonly city = signal('');
  readonly state = signal('');
  readonly zipCode = signal('');

  readonly validationError = signal<string | null>(null);
  readonly saveMessage = signal<string | null>(null);
  readonly saveMessageType = signal<'success' | 'error'>('success');

  readonly kycMessage = signal<string | null>(null);
  readonly kycMessageType = signal<'success' | 'error'>('success');

  readonly accounts = signal<AccountOverview[]>([]);
  readonly copiedField = signal<string | null>(null);

  constructor(
    private readonly profileService: ProfileService,
    private readonly accountService: AccountService,
  ) {}

  ngOnInit(): void {
    // No userId needed - the backend derives it from the JWT on the request.
    this.profileService.getKycStatus().subscribe({
      next: (status) => {
        this.kycStatus.set(status);
        this.kycLoading.set(false);
      },
      error: () => {
        this.kycLoading.set(false);
        this.kycError.set(true);
      },
    });
    // Pre-fill from what's already on file, so nobody retypes - and mistypes - details the account
    // was registered with. Failing silently is deliberate: not being able to read the form back is
    // no reason to stop somebody verifying, and an empty form still submits perfectly well.
    this.profileService.getContactInfo().subscribe({
      next: (info) => this.applyContactInfo(info),
      error: () => undefined,
    });
    this.accountService.getAccounts().subscribe((accounts) => this.accounts.set(accounts));
  }

  // Every field is null until the user has submitted the form at least once, and a record can be
  // partial - fall back to the empty string each signal already holds rather than writing a null
  // into an <input>.
  private applyContactInfo(info: ContactInfoView | null): void {
    if (!info) {
      return;
    }

    this.legalName.set(info.legalName ?? '');
    // Already ISO yyyy-MM-dd, which is exactly what <input type="date"> wants.
    this.dateOfBirth.set(info.dateOfBirth ?? '');
    // Comes back in E.164 ("+15712856947"), which PHONE_PATTERN accepts, so it round-trips unedited.
    this.phoneNumber.set(info.phoneNumber ?? '');
    this.addressLine1.set(info.addressLine1 ?? '');
    this.addressLine2.set(info.addressLine2 ?? '');
    this.city.set(info.city ?? '');
    this.state.set(info.state ?? '');
    this.zipCode.set(info.zipCode ?? '');
  }

  // The Copy button is hidden when there's no value, but guard anyway - clipboard.writeText(null)
  // rejects, and an unhandled rejection here would be invisible to the user.
  copyToClipboard(field: string, value: string | null | undefined): void {
    if (!value) {
      return;
    }
    navigator.clipboard.writeText(value).then(() => {
      this.copiedField.set(field);
      setTimeout(() => this.copiedField.set(null), 2000);
    });
  }

  submit(): void {
    this.validationError.set(null);
    this.saveMessage.set(null);
    this.kycMessage.set(null);

    if (!this.legalName().trim()) {
      this.validationError.set('Please enter your full legal name.');
      return;
    }
    if (!this.dateOfBirth()) {
      this.validationError.set('Please enter your date of birth.');
      return;
    }
    if (!this.isAtLeastMinimumAge(this.dateOfBirth())) {
      this.validationError.set(`You must be at least ${MINIMUM_AGE_YEARS} years old to open an account.`);
      return;
    }
    if (!PHONE_PATTERN.test(this.phoneNumber())) {
      this.validationError.set('Please enter a valid phone number (e.g. 571-285-6947 or +15712856947).');
      return;
    }
    if (!this.addressLine1() || !this.city() || !this.state() || !this.zipCode()) {
      this.validationError.set('Please fill in all required address fields.');
      return;
    }

    const wasUnverified = this.kycStatus() !== 'APPROVED';

    this.profileService
      .updateContactInfo({
        legalName: this.legalName(),
        dateOfBirth: this.dateOfBirth(),
        phoneNumber: this.phoneNumber(),
        addressLine1: this.addressLine1(),
        ...(this.addressLine2() ? { addressLine2: this.addressLine2() } : {}),
        city: this.city(),
        state: this.state(),
        zipCode: this.zipCode(),
      })
      .subscribe({
        next: (status) => {
          this.saveMessageType.set('success');
          this.saveMessage.set('Your details have been saved.');

          // The backend hands back the status this submission produced, so the banner and the
          // Transfers-enabled state update without a second request.
          this.kycStatus.set(status);
          if (status === 'APPROVED' && wasUnverified) {
            this.kycMessageType.set('success');
            this.kycMessage.set('Your identity has been verified. Transfers are now enabled.');
          }
        },
        error: (error: unknown) => {
          this.saveMessageType.set('error');
          // A 409 here is always a phone number already registered to another account. The server
          // says so itself, so that wording wins; the fallback only covers a conflict that arrives
          // without a body, where a blanket "something went wrong" would send the user hunting
          // through fields that are actually fine.
          const isConflict = error instanceof HttpErrorResponse && error.status === 409;
          this.saveMessage.set(
            isConflict
              ? extractApiError(error, 'That phone number is already registered to another account.')
              : extractApiError(error),
          );
        },
      });
  }

  // <input type="date"> gives an ISO yyyy-MM-dd string. Comparing against the date exactly
  // MINIMUM_AGE_YEARS ago avoids the off-by-one that month/day arithmetic invites around birthdays.
  private isAtLeastMinimumAge(isoDate: string): boolean {
    const birthDate = new Date(isoDate);
    if (Number.isNaN(birthDate.getTime())) {
      return false;
    }

    const cutoff = new Date();
    cutoff.setFullYear(cutoff.getFullYear() - MINIMUM_AGE_YEARS);
    return birthDate <= cutoff;
  }
}
