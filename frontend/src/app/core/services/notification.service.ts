import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { NotificationPage } from '../models/notification.models';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly baseUrl = environment.notificationApiUrl;

  constructor(private readonly http: HttpClient) {}

  getNotifications(page?: number): Observable<NotificationPage> {
    let params = new HttpParams();
    if (page !== undefined) {
      params = params.set('page', page);
    }

    return this.http.get<NotificationPage>(this.baseUrl, { params });
  }
}
