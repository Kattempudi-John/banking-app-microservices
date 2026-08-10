import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ContactInfo, KycStatus, UserPreference } from '../models/profile.models';

@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly baseUrl = environment.profileApiUrl;

  constructor(private readonly http: HttpClient) {}

  updateContactInfo(request: ContactInfo): Observable<unknown> {
    return this.http.put(`${this.baseUrl}/profiles/me/contact-info`, request);
  }

  // No userId in the path on purpose - the backend reads it from the JWT. Passing one from the
  // client meant anyone could ask for somebody else's KYC status just by changing the number.
  getKycStatus(): Observable<KycStatus> {
    return this.http
      .get<{ status: KycStatus }>(`${this.baseUrl}/profiles/me/kyc-status`)
      .pipe(map((response) => response.status));
  }

  // Demo-only: simulates the KYC vendor's webhook callback for the logged-in user themselves.
  // 404s if the backend's app.demo.enabled flag is off.
  simulateKycApproval(): Observable<KycStatus> {
    return this.http
      .post<{ status: KycStatus }>(`${this.baseUrl}/profiles/kyc/simulate-approval`, {})
      .pipe(map((response) => response.status));
  }

  // Like getKycStatus above, no userId in the path - the backend takes it from the JWT. This
  // response carries an email address, so letting the client name the user was worth removing.
  getPreferences(): Observable<UserPreference> {
    return this.http.get<UserPreference>(`${this.baseUrl}/profile/alerts/me`);
  }

  updateAlertThreshold(amount: number): Observable<unknown> {
    return this.http.put(
      `${this.baseUrl}/profile/alerts/threshold`,
      { alertThresholdAmount: amount },
      { responseType: 'text' },
    );
  }

  updateDailySummary(enabled: boolean, timezone: string): Observable<unknown> {
    return this.http.put(
      `${this.baseUrl}/profile/alerts/daily-summary`,
      { dailySummaryEnabled: enabled, timezone },
      { responseType: 'text' },
    );
  }
}
