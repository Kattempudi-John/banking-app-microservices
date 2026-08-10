import { Component, OnInit, signal } from '@angular/core';

import { NotificationService } from '../../core/services/notification.service';
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
