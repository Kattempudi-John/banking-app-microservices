import { Component, OnInit, computed, signal } from '@angular/core';
import { forkJoin } from 'rxjs';

import { AccountService } from '../../core/services/account.service';
import { TransferService } from '../../core/services/transfer.service';
import { AccountOverview } from '../../core/models/account.models';
import { HistoryEntry, HistoryKind, HistoryStatus } from '../../core/models/history.models';
import { TableColumn, TableComponent } from '../../shared/table/table.component';
import { ButtonComponent } from '../../shared/button/button.component';
import { NavComponent } from '../../shared/nav/nav.component';

type KindFilter = 'ALL' | HistoryKind;
type StatusFilter = 'ALL' | HistoryStatus;

@Component({
  selector: 'app-history',
  standalone: true,
  imports: [TableComponent, ButtonComponent, NavComponent],
  templateUrl: './history.component.html',
  styleUrl: './history.component.css',
})
export class HistoryComponent implements OnInit {
  readonly columns: TableColumn[] = [
    { key: 'createdAt', label: 'Date' },
    { key: 'kind', label: 'Type' },
    { key: 'description', label: 'Description' },
    { key: 'amount', label: 'Amount' },
    { key: 'status', label: 'Status' },
  ];

  readonly accounts = signal<AccountOverview[]>([]);
  readonly entries = signal<HistoryEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal(false);

  readonly accountFilter = signal<number | null>(null);
  readonly kindFilter = signal<KindFilter>('ALL');
  readonly statusFilter = signal<StatusFilter>('ALL');
  readonly fromFilter = signal('');
  readonly toFilter = signal('');

  // Kind/status are filtered client-side on the already-merged list - account/date range are the
  // only filters both backend endpoints understand server-side (see load() below).
  readonly filteredEntries = computed(() => {
    const kind = this.kindFilter();
    const status = this.statusFilter();
    return this.entries().filter(
      (entry) => (kind === 'ALL' || entry.kind === kind) && (status === 'ALL' || entry.status === status),
    );
  });

  constructor(
    private readonly accountService: AccountService,
    private readonly transferService: TransferService,
  ) {}

  ngOnInit(): void {
    this.accountService.getAccounts().subscribe((accounts) => this.accounts.set(accounts));
    this.load();
  }

  onAccountFilterChange(value: string): void {
    this.accountFilter.set(value ? Number(value) : null);
    this.load();
  }

  onKindFilterChange(value: string): void {
    this.kindFilter.set(value as KindFilter);
  }

  onStatusFilterChange(value: string): void {
    this.statusFilter.set(value as StatusFilter);
  }

  onFromChange(value: string): void {
    this.fromFilter.set(value);
    this.load();
  }

  onToChange(value: string): void {
    this.toFilter.set(value);
    this.load();
  }

  retry(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);

    const accountId = this.accountFilter() ?? undefined;
    const from = this.fromFilter() ? `${this.fromFilter()}T00:00:00` : undefined;
    const to = this.toFilter() ? `${this.toFilter()}T23:59:59` : undefined;

    forkJoin({
      ledger: this.accountService.getAllTransactions({ accountId, from, to }),
      wires: this.transferService.getTransferHistory({ accountId, from, to }),
    }).subscribe({
      next: ({ ledger, wires }) => {
        const ledgerEntries: HistoryEntry[] = ledger.content.map((t) => ({
          key: `ledger-${t.id}`,
          createdAt: t.createdAt,
          kind: t.transactionType as HistoryKind,
          accountId: t.accountId,
          description: t.description,
          amount: t.amount,
          status: 'COMPLETED',
        }));

        const wireEntries: HistoryEntry[] = wires.content.map((w) => ({
          key: `wire-${w.transactionId}`,
          createdAt: w.createdAt,
          kind: w.destinationAccountId != null ? 'ON_US_WIRE' : 'EXTERNAL_WIRE',
          accountId: w.accountId,
          description: w.description,
          amount: w.amount,
          status: w.status,
        }));

        const merged = [...ledgerEntries, ...wireEntries].sort(
          (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
        );

        this.entries.set(merged);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
