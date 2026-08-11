import { Component, OnInit, computed, signal } from '@angular/core';

import { NotificationService } from '../../core/services/notification.service';
import { toReadableMessage } from '../../core/notification-message';
import { Notification } from '../../core/models/notification.models';
import { TableColumn, TableComponent } from '../../shared/table/table.component';
import { ButtonComponent } from '../../shared/button/button.component';
import { NavComponent } from '../../shared/nav/nav.component';

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

  constructor(private readonly notificationService: NotificationService) {}

  ngOnInit(): void {
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

    this.notificationService.getNotifications(page).subscribe({
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
