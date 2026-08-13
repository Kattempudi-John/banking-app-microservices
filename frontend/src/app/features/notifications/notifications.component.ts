import { Component, OnInit, computed, signal } from '@angular/core';

import { NotificationFilters, NotificationService } from '../../core/services/notification.service';
import { toReadableMessage } from '../../core/notification-message';
import {
  Notification,
  NotificationChannel,
  NotificationDeliveryStatus,
  NotificationType,
} from '../../core/models/notification.models';
import { TableColumn, TableComponent } from '../../shared/table/table.component';
import { ButtonComponent } from '../../shared/button/button.component';
import { NavComponent } from '../../shared/nav/nav.component';

// The empty string is what the "All" option carries, i.e. no constraint on that field.
type TypeFilter = '' | NotificationType;
type ChannelFilter = '' | NotificationChannel;
type StatusFilter = '' | NotificationDeliveryStatus;

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [TableComponent, ButtonComponent, NavComponent],
  templateUrl: './notifications.component.html',
  styleUrl: './notifications.component.css',
})
export class NotificationsComponent implements OnInit {
  readonly columns: TableColumn[] = [
    { key: 'createdAt', label: 'Date' },
    { key: 'type', label: 'Type' },
    { key: 'channel', label: 'Channel' },
    { key: 'message', label: 'Message' },
    { key: 'status', label: 'Status' },
  ];

  readonly notifications = signal<Notification[]>([]);

  // What the table actually renders. The stored notification is left untouched - it is the record of
  // what was dispatched - and only the presentation is reshaped here: the email types hold full HTML
  // documents, and every cell renders as text, so those rows previously showed raw markup.
  readonly displayRows = computed(() =>
    this.notifications().map((notification) => ({
      ...notification,
      createdAt: this.formatDate(notification.createdAt),
      message: toReadableMessage(notification.message),
    })),
  );

  readonly currentPage = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(false);
  readonly error = signal(false);

  readonly typeFilter = signal<TypeFilter>('');
  readonly channelFilter = signal<ChannelFilter>('');
  readonly statusFilter = signal<StatusFilter>('');
  readonly fromFilter = signal('');
  readonly toFilter = signal('');

  // Only the fields the user actually picked land here. An unpicked filter has to be absent rather
  // than empty: the API reads a present-but-empty value as a constraint nothing can satisfy.
  readonly activeFilters = computed<NotificationFilters>(() => {
    const filters: NotificationFilters = {};

    const type = this.typeFilter();
    if (type) {
      filters.type = type;
    }
    const channel = this.channelFilter();
    if (channel) {
      filters.channel = channel;
    }
    const status = this.statusFilter();
    if (status) {
      filters.status = status;
    }
    const from = this.fromFilter();
    if (from) {
      filters.from = `${from}T00:00:00`;
    }
    const to = this.toFilter();
    if (to) {
      // The date input yields a bare day, and the bound is inclusive, so it has to run to the end
      // of that day - a plain midnight would hide everything that happened on the day picked.
      filters.to = `${to}T23:59:59`;
    }

    return filters;
  });

  readonly hasActiveFilters = computed(() => Object.keys(this.activeFilters()).length > 0);

  constructor(private readonly notificationService: NotificationService) {}

  ngOnInit(): void {
    this.loadPage(0);
  }

  // Every filter change restarts at the first page: keeping the old page number would land the user
  // past the end of a narrower result set and read as their notifications having vanished.
  onTypeFilterChange(value: string): void {
    this.typeFilter.set(value as TypeFilter);
    this.loadPage(0);
  }

  onChannelFilterChange(value: string): void {
    this.channelFilter.set(value as ChannelFilter);
    this.loadPage(0);
  }

  onStatusFilterChange(value: string): void {
    this.statusFilter.set(value as StatusFilter);
    this.loadPage(0);
  }

  onFromChange(value: string): void {
    this.fromFilter.set(value);
    this.loadPage(0);
  }

  onToChange(value: string): void {
    this.toFilter.set(value);
    this.loadPage(0);
  }

  clearFilters(): void {
    this.typeFilter.set('');
    this.channelFilter.set('');
    this.statusFilter.set('');
    this.fromFilter.set('');
    this.toFilter.set('');
    this.loadPage(0);
  }

  goToPage(page: number): void {
    this.loadPage(page);
  }

  retry(): void {
    this.loadPage(this.currentPage());
  }

  // The API returns a raw LocalDateTime ("2026-08-10T20:40:35.79046"), which was being printed
  // verbatim including the microseconds. Falls back to the original string rather than showing
  // "Invalid Date" if anything unexpected ever arrives.
  private formatDate(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      return value;
    }

    return parsed.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  private loadPage(page: number): void {
    this.loading.set(true);
    this.error.set(false);

    // Paging and retrying both come through here, so the active filters ride along with them.
    const filters = this.activeFilters();
    const request = this.hasActiveFilters()
      ? this.notificationService.getNotifications(page, filters)
      : this.notificationService.getNotifications(page);

    request.subscribe({
      next: (result) => {
        this.notifications.set(result.content);
        this.currentPage.set(result.number);
        this.totalPages.set(result.totalPages);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
