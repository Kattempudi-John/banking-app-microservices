import { Component, OnInit, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';

import { extractApiError } from '../../core/api-error';
import { AccountService } from '../../core/services/account.service';
import { ProfileService } from '../../core/services/profile.service';
import { AccountOverview, AccountType } from '../../core/models/account.models';
import { KycStatus } from '../../core/models/profile.models';
import { TableColumn, TableComponent } from '../../shared/table/table.component';
import { ButtonComponent } from '../../shared/button/button.component';
import { NavComponent } from '../../shared/nav/nav.component';
import { ModalComponent } from '../../shared/modal/modal.component';
import { InputComponent } from '../../shared/input/input.component';
import { AlertBannerComponent } from '../../shared/alert-banner/alert-banner.component';
import { KycNoticeComponent } from '../../shared/kyc-notice/kyc-notice.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    TableComponent,
    ButtonComponent,
    NavComponent,
    ModalComponent,
    FormsModule,
    InputComponent,
    AlertBannerComponent,
    KycNoticeComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  readonly columns: TableColumn[] = [
    { key: 'accountType', label: 'Type' },
    { key: 'maskedAccountNumber', label: 'Account' },
    { key: 'status', label: 'Status' },
    { key: 'availableBalance', label: 'Balance' },
  ];

  readonly accounts = signal<AccountOverview[]>([]);
  readonly loading = signal(false);
  readonly error = signal(false);

  readonly depositModalOpen = signal(false);
  readonly depositAccountId = signal<number | null>(null);
  readonly depositAmount = signal('');
  readonly depositError = signal<string | null>(null);
  readonly depositSuccess = signal<string | null>(null);

  readonly openAccountModalOpen = signal(false);
  readonly newAccountType = signal<AccountType>('SAVINGS');
  readonly openAccountError = signal<string | null>(null);

  // Opening an account and adding funds are both KYC-gated in account-service. Reading the status
  // here isn't the enforcement - the backend's 403 is - it just means an unverified user is told
  // why up front instead of finding out by filling in a form and having it rejected.
  readonly kycStatus = signal<KycStatus | null>(null);
  readonly kycApproved = computed(() => this.kycStatus() === 'APPROVED');

  constructor(
    private readonly accountService: AccountService,
    private readonly profileService: ProfileService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.loadAccounts();
    this.loadKycStatus();
  }

  loadKycStatus(): void {
    this.profileService.getKycStatus().subscribe({
      next: (status) => this.kycStatus.set(status),
      // Left null on failure, which reads as "not approved" and keeps the actions hidden. Showing
      // buttons that the backend would refuse anyway would be the worse guess.
      error: () => this.kycStatus.set(null),
    });
  }

  loadAccounts(): void {
    this.loading.set(true);
    this.error.set(false);

    this.accountService.getAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  onRowClick(row: AccountOverview): void {
    this.router.navigate(['/accounts', row.accountId, 'transactions']);
  }

  openDepositModal(): void {
    this.depositError.set(null);
    this.depositSuccess.set(null);
    this.depositAmount.set('');
    this.depositAccountId.set(this.accounts()[0]?.accountId ?? null);
    this.depositModalOpen.set(true);
  }

  closeDepositModal(): void {
    this.depositModalOpen.set(false);
  }

  onDepositAccountChange(value: string): void {
    this.depositAccountId.set(value ? Number(value) : null);
  }

  submitDeposit(): void {
    this.depositError.set(null);
    this.depositSuccess.set(null);

    const amountNum = Number(this.depositAmount());
    if (!amountNum || amountNum <= 0) {
      this.depositError.set('Please enter a positive amount.');
      return;
    }
    if (this.depositAccountId() === null) {
      this.depositError.set('Please select an account.');
      return;
    }

    this.accountService.depositFunds(this.depositAccountId()!, amountNum).subscribe({
      next: (updated) => {
        this.accounts.update((accounts) =>
          accounts.map((a) => (a.accountId === updated.accountId ? updated : a)),
        );
        this.depositSuccess.set(`Deposited $${amountNum.toFixed(2)} successfully.`);
        this.depositAmount.set('');
      },
      error: (error: unknown) => {
        this.depositError.set(extractApiError(error, 'Deposit amount is invalid.'));
      },
    });
  }

  openNewAccountModal(): void {
    this.openAccountError.set(null);
    this.newAccountType.set('SAVINGS');
    this.openAccountModalOpen.set(true);
  }

  closeNewAccountModal(): void {
    this.openAccountModalOpen.set(false);
  }

  onNewAccountTypeChange(value: string): void {
    this.newAccountType.set(value as AccountType);
  }

  submitOpenAccount(): void {
    this.openAccountError.set(null);

    this.accountService.openAccount(this.newAccountType()).subscribe({
      next: (created) => {
        this.accounts.update((accounts) => [...accounts, created]);
        this.openAccountModalOpen.set(false);
      },
      error: (error: unknown) => {
        this.openAccountError.set(extractApiError(error, 'Could not open account.'));
      },
    });
  }
}
