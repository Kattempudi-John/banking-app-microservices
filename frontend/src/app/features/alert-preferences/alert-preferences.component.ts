import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { extractApiError } from '../../core/api-error';
import { ProfileService } from '../../core/services/profile.service';
import { ButtonComponent } from '../../shared/button/button.component';
import { AlertBannerComponent } from '../../shared/alert-banner/alert-banner.component';
import { NavComponent } from '../../shared/nav/nav.component';
import { InputComponent } from '../../shared/input/input.component';

// The backend speaks 0-23, but nobody reads their own schedule that way. Both ends of the clock are
// the easy ones to get wrong: hour 0 is "12:00 AM" and hour 12 is "12:00 PM", not "0:00 AM" and
// "12:00 AM" - hence the modulo landing on 12 rather than 0.
function formatHour(hour: number): string {
  const period = hour < 12 ? 'AM' : 'PM';
  return `${hour % 12 === 0 ? 12 : hour % 12}:00 ${period}`;
}

@Component({
  selector: 'app-alert-preferences',
  standalone: true,
  imports: [FormsModule, ButtonComponent, AlertBannerComponent, NavComponent, InputComponent],
  templateUrl: './alert-preferences.component.html',
  styleUrl: './alert-preferences.component.css',
})
export class AlertPreferencesComponent implements OnInit {
  readonly threshold = signal('');
  readonly dailySummaryEnabled = signal(false);
  readonly timezone = signal('');
  // Same default the backend applies to anyone who has never picked an hour.
  readonly dailySummaryHour = signal(8);

  readonly hourOptions = Array.from({ length: 24 }, (_, hour) => ({ value: hour, label: formatHour(hour) }));

  readonly thresholdError = signal<string | null>(null);
  readonly thresholdMessage = signal<string | null>(null);
  readonly thresholdMessageType = signal<'success' | 'error'>('success');

  readonly dailySummaryMessage = signal<string | null>(null);
  readonly dailySummaryMessageType = signal<'success' | 'error'>('success');

  constructor(
    private readonly profileService: ProfileService,
  ) {}

  ngOnInit(): void {
    // No userId needed - the backend derives it from the JWT on the request.
    this.profileService.getPreferences().subscribe((pref) => {
      this.threshold.set(String(pref.alertThresholdAmount));
      this.dailySummaryEnabled.set(pref.dailySummaryEnabled);
      this.timezone.set(pref.timezone);
      this.dailySummaryHour.set(pref.dailySummaryHour);
    });
  }

  saveThreshold(): void {
    this.thresholdError.set(null);
    this.thresholdMessage.set(null);

    const amount = Number(this.threshold());
    if (!amount || amount <= 0) {
      this.thresholdError.set('Please enter a positive amount.');
      return;
    }

    this.profileService.updateAlertThreshold(amount).subscribe({
      next: () => {
        this.thresholdMessageType.set('success');
        this.thresholdMessage.set('Alert threshold saved.');
      },
      error: () => {
        this.thresholdMessageType.set('error');
        this.thresholdMessage.set('Something went wrong. Please try again.');
      },
    });
  }

  saveDailySummary(): void {
    this.dailySummaryMessage.set(null);

    this.profileService
      .updateDailySummary(this.dailySummaryEnabled(), this.timezone(), this.dailySummaryHour())
      .subscribe({
        next: () => {
          this.dailySummaryMessageType.set('success');
          this.dailySummaryMessage.set('Alert preferences saved.');
        },
        // The backend rejects an out-of-range hour with a reason worth reading, and a generic line
        // would leave the user with no idea which of the two controls it objected to.
        error: (error: unknown) => {
          this.dailySummaryMessageType.set('error');
          this.dailySummaryMessage.set(extractApiError(error));
        },
      });
  }
}
