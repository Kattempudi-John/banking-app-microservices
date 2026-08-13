import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  NotificationChannel,
  NotificationDeliveryStatus,
  NotificationPage,
  NotificationType,
} from '../models/notification.models';

// Every field is optional and independently combinable - an absent field means "no constraint on
// that field", so it must never reach the API as an empty value.
export interface NotificationFilters {
  type?: NotificationType;
  channel?: NotificationChannel;
  status?: NotificationDeliveryStatus;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly baseUrl = environment.notificationApiUrl;

  constructor(private readonly http: HttpClient) {}

  getNotifications(page?: number, filters: NotificationFilters = {}): Observable<NotificationPage> {
    let params = new HttpParams();
    if (page !== undefined) {
      params = params.set('page', page);
    }
    if (filters.type) {
      params = params.set('type', filters.type);
    }
    if (filters.channel) {
      params = params.set('channel', filters.channel);
    }
    if (filters.status) {
      params = params.set('status', filters.status);
    }
    if (filters.from) {
      params = params.set('from', filters.from);
    }
    if (filters.to) {
      params = params.set('to', filters.to);
    }

    return this.http.get<NotificationPage>(this.baseUrl, { params });
  }
}
