import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { HistoryComponent } from './history.component';
import { AccountService } from '../../core/services/account.service';
import { TransferService } from '../../core/services/transfer.service';
import { AccountOverview, TransactionPage } from '../../core/models/account.models';
import { WireTransferPage } from '../../core/models/history.models';
import { AuthService } from '../../core/auth.service';

describe('HistoryComponent', () => {
  let fixture: ComponentFixture<HistoryComponent>;
  let accountServiceSpy: jasmine.SpyObj<AccountService>;
  let transferServiceSpy: jasmine.SpyObj<TransferService>;

  const mockAccounts: AccountOverview[] = [
    { accountId: 1, accountType: 'CHECKING', availableBalance: 1000, routingNumber: '021000021', maskedAccountNumber: '****1234', accountNumber: '9876541234', iban: 'XB00021000021123456789012', swiftCode: 'XBUSUS31', status: 'ACTIVE' },
  ];

  const ledgerPage: TransactionPage = {
    content: [
      { id: 1, accountId: 1, transactionType: 'DEBIT', amount: 4.5, description: 'Coffee Shop', createdAt: '2026-08-01T10:00:00Z' },
    ],
    totalPages: 1,
    totalElements: 1,
    number: 0,
    size: 50,
  };

  const wirePage: WireTransferPage = {
    content: [
      {
        transactionId: 'txn-1',
        accountId: 1,
        amount: 7500,
        status: 'PENDING_APPROVAL',
        description: 'External Wire to Jane Doe',
        iban: 'GB29NWBK60161331926819',
        swiftCode: 'NWBKGB2L',
        beneficiaryName: 'Jane Doe',
        destinationAccountId: null,
        createdAt: '2026-08-02T10:00:00Z',
      },
    ],
    totalPages: 1,
    totalElements: 1,
    number: 0,
    size: 50,
  };

  function setup(
    ledger: TransactionPage = ledgerPage,
    wires: WireTransferPage = wirePage,
  ): void {
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts', 'getAllTransactions']);
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    accountServiceSpy.getAllTransactions.and.returnValue(of(ledger));

    transferServiceSpy = jasmine.createSpyObj('TransferService', ['getTransferHistory']);
    transferServiceSpy.getTransferHistory.and.returnValue(of(wires));

    TestBed.configureTestingModule({
      imports: [HistoryComponent],
      providers: [
        provideRouter([]),
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: TransferService, useValue: transferServiceSpy },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }) },
      ],
    });

    fixture = TestBed.createComponent(HistoryComponent);
    fixture.detectChanges();
  }

  it('merges ledger transactions and wire records into one chronological list', () => {
    setup();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Coffee Shop');
    expect(text).toContain('External Wire to Jane Doe');
    expect(text).toContain('PENDING_APPROVAL');
  });

  it('sorts the merged entries newest first', () => {
    setup();
    const rows: HTMLTableRowElement[] = Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
    // wirePage's entry (2026-08-02) is newer than ledgerPage's (2026-08-01), so it should render first.
    expect(rows[0].textContent).toContain('External Wire to Jane Doe');
    expect(rows[1].textContent).toContain('Coffee Shop');
  });

  it('labels an on-us wire (destinationAccountId set) distinctly from a genuinely external one', () => {
    const onUsWire: WireTransferPage = {
      ...wirePage,
      content: [{ ...wirePage.content[0], destinationAccountId: 99, status: 'COMPLETED' }],
    };
    setup(ledgerPage, onUsWire);

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#kindFilter');
    select.value = 'ON_US_WIRE';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('External Wire to Jane Doe');
  });

  it('shows an empty state when there is no history at all', () => {
    setup(
      { content: [], totalPages: 0, totalElements: 0, number: 0, size: 50 },
      { content: [], totalPages: 0, totalElements: 0, number: 0, size: 50 },
    );
    expect(fixture.nativeElement.textContent).toContain('No history found');
  });

  it('shows an error state with a retry button when either source fails to load', () => {
    accountServiceSpy = jasmine.createSpyObj('AccountService', ['getAccounts', 'getAllTransactions']);
    accountServiceSpy.getAccounts.and.returnValue(of(mockAccounts));
    accountServiceSpy.getAllTransactions.and.returnValue(throwError(() => new Error('network error')));
    transferServiceSpy = jasmine.createSpyObj('TransferService', ['getTransferHistory']);
    transferServiceSpy.getTransferHistory.and.returnValue(of(wirePage));

    TestBed.configureTestingModule({
      imports: [HistoryComponent],
      providers: [
        provideRouter([]),
        { provide: AccountService, useValue: accountServiceSpy },
        { provide: TransferService, useValue: transferServiceSpy },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }) },
      ],
    });
    fixture = TestBed.createComponent(HistoryComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Unable to load your history');
  });

  it('re-fetches both sources with the accountId filter when the user filters by account', () => {
    setup();
    accountServiceSpy.getAllTransactions.calls.reset();
    transferServiceSpy.getTransferHistory.calls.reset();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#accountFilter');
    select.value = '1';
    select.dispatchEvent(new Event('change'));

    expect(accountServiceSpy.getAllTransactions).toHaveBeenCalledWith(
      jasmine.objectContaining({ accountId: 1 }),
    );
    expect(transferServiceSpy.getTransferHistory).toHaveBeenCalledWith(
      jasmine.objectContaining({ accountId: 1 }),
    );
  });

  it('filters the merged list client-side by kind without re-fetching', () => {
    setup();
    accountServiceSpy.getAllTransactions.calls.reset();
    transferServiceSpy.getTransferHistory.calls.reset();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#kindFilter');
    select.value = 'DEBIT';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Coffee Shop');
    expect(fixture.nativeElement.textContent).not.toContain('External Wire to Jane Doe');
    expect(accountServiceSpy.getAllTransactions).not.toHaveBeenCalled();
    expect(transferServiceSpy.getTransferHistory).not.toHaveBeenCalled();
  });
});
