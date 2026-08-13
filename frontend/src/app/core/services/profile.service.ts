import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ContactInfo, ContactInfoView, KycStatus, UserPreference } from '../models/profile.models';

@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly baseUrl = environment.profileApiUrl;

  constructor(private readonly http: HttpClient) {}

  // Returns the resulting KYC status, because submitting this form IS the verification step - the
  // backend approves off the back of a complete identity, so the caller needs the outcome without
  // re-reading it and racing the commit.
  updateContactInfo(request: ContactInfo): Observable<KycStatus> {
    return this.http
      .put<{ message: string; kycStatus: KycStatus }>(`${this.baseUrl}/profiles/me/contact-info`, request)
      .pipe(map((response) => response.kycStatus));
  }

  // No userId in the path on purpose - the backend reads it from the JWT. Passing one from the
  // client meant anyone could ask for somebody else's KYC status just by changing the number.
  getKycStatus(): Observable<KycStatus> {
    return this.http
      .get<{ status: KycStatus }>(`${this.baseUrl}/profiles/me/kyc-status`)
      .pipe(map((response) => response.status));
  }

  // Same JWT-derived user as getKycStatus above, no userId in the path. Reads back what was last
  // submitted so the form can be pre-filled - people were re-typing a different phone number than
  // the one their account was registered with, because the form always started blank.
  getContactInfo(): Observable<ContactInfoView> {
    return this.http.get<ContactInfoView>(`${this.baseUrl}/profiles/me/contact-info`);
  }

  // simulateKycApproval() is gone along with the endpoint behind it. Verification now happens by
  // submitting the identity form - see updateContactInfo above, which returns the resulting status.

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

  updateDailySummary(enabled: boolean, timezone: string, hour: number): Observable<unknown> {
    return this.http.put(
      `${this.baseUrl}/profile/alerts/daily-summary`,
      { dailySummaryEnabled: enabled, timezone, dailySummaryHour: hour },
      { responseType: 'text' },
    );
  }
}
