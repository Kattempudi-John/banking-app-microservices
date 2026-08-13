import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AlertPreferencesComponent } from './alert-preferences.component';
import { ProfileService } from '../../core/services/profile.service';
import { AuthService } from '../../core/auth.service';
import { UserPreference } from '../../core/models/profile.models';

describe('AlertPreferencesComponent', () => {
  let fixture: ComponentFixture<AlertPreferencesComponent>;
  let profileServiceSpy: jasmine.SpyObj<ProfileService>;

  const mockPreference: UserPreference = {
    userId: 42,
    alertThresholdAmount: 500,
    dailySummaryEnabled: true,
    timezone: 'America/New_York',
    dailySummaryHour: 17,
  };

  async function setup(pref: UserPreference = mockPreference): Promise<void> {
    profileServiceSpy = jasmine.createSpyObj('ProfileService', [
      'getPreferences',
      'updateAlertThreshold',
      'updateDailySummary',
    ]);
    profileServiceSpy.getPreferences.and.returnValue(of(pref));
    const authServiceSpy = jasmine.createSpyObj('AuthService', ['logout'], { userId: () => 42 });
    authServiceSpy.logout.and.returnValue(of({}));

    TestBed.configureTestingModule({
      imports: [AlertPreferencesComponent],
      providers: [
        provideRouter([]),
        { provide: ProfileService, useValue: profileServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });

    fixture = TestBed.createComponent(AlertPreferencesComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function thresholdInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('app-input[id="threshold"] input');
  }

  function dailySummaryToggle(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[name="dailySummaryEnabled"]');
  }

  function timezoneSelect(): HTMLSelectElement {
    return fixture.nativeElement.querySelector('select[name="timezone"]');
  }

  function hourSelect(): HTMLSelectElement {
    return fixture.nativeElement.querySelector('select[name="dailySummaryHour"]');
  }

  function clickButtonContaining(text: string): void {
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.includes(text))!.click();
  }

  it('loads and pre-fills existing preferences on init', async () => {
    await setup();
    expect(profileServiceSpy.getPreferences).toHaveBeenCalled();
    expect(thresholdInput().value).toBe('500');
    expect(dailySummaryToggle().checked).toBeTrue();
    expect(timezoneSelect().value).toBe('America/New_York');
    expect(hourSelect().value).toBe('17');
  });

  it('saves a valid threshold and shows a confirmation', async () => {
    await setup();
    profileServiceSpy.updateAlertThreshold.and.returnValue(of({}));

    thresholdInput().value = '750';
    thresholdInput().dispatchEvent(new Event('input'));
    await fixture.whenStable();
    clickButtonContaining('Save Threshold');
    fixture.detectChanges();

    expect(profileServiceSpy.updateAlertThreshold).toHaveBeenCalledWith(750);
    expect(fixture.nativeElement.textContent).toContain('saved');
  });

  it('shows inline validation and does not save a non-positive threshold', async () => {
    await setup();
    thresholdInput().value = '0';
    thresholdInput().dispatchEvent(new Event('input'));
    await fixture.whenStable();
    clickButtonContaining('Save Threshold');
    fixture.detectChanges();

    expect(profileServiceSpy.updateAlertThreshold).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('positive amount');
  });

  it('saves daily summary settings and shows a confirmation', async () => {
    await setup();
    profileServiceSpy.updateDailySummary.and.returnValue(of({}));

    timezoneSelect().value = 'America/Chicago';
    timezoneSelect().dispatchEvent(new Event('change'));
    clickButtonContaining('Save Alerts');
    fixture.detectChanges();

    expect(profileServiceSpy.updateDailySummary).toHaveBeenCalledWith(true, 'America/Chicago', 17);
    expect(fixture.nativeElement.textContent).toContain('saved');
  });

  it('does not require a timezone when daily summary is turned off', async () => {
    await setup({ ...mockPreference, dailySummaryEnabled: false, timezone: '' });
    profileServiceSpy.updateDailySummary.and.returnValue(of({}));

    clickButtonContaining('Save Alerts');
    fixture.detectChanges();

    expect(profileServiceSpy.updateDailySummary).toHaveBeenCalledWith(false, '', 17);
  });

  it('sends the chosen hour as a number when the summary time is changed', async () => {
    await setup();
    profileServiceSpy.updateDailySummary.and.returnValue(of({}));

    hourSelect().value = '6';
    hourSelect().dispatchEvent(new Event('change'));
    clickButtonContaining('Save Alerts');
    fixture.detectChanges();

    expect(profileServiceSpy.updateDailySummary).toHaveBeenCalledWith(true, 'America/New_York', 6);
  });

  // Midnight and noon are where a naive 12-hour conversion goes wrong: hour 0 reads "0:00 AM" and
  // hour 12 flips to "12:00 AM" if the meridiem is taken from the wrong side of the comparison.
  it('labels midnight and noon as 12:00 AM and 12:00 PM', async () => {
    await setup();
    const labels = Array.from(hourSelect().options).map((option) => option.textContent?.trim());

    expect(labels.length).toBe(24);
    expect(labels[0]).toBe('12:00 AM');
    expect(labels[12]).toBe('12:00 PM');
    expect(labels[8]).toBe('8:00 AM');
    expect(labels[13]).toBe('1:00 PM');
  });

  it('shows the reason the server gave when it rejects the summary hour', async () => {
    await setup();
    profileServiceSpy.updateDailySummary.and.returnValue(
      throwError(() => new HttpErrorResponse({
        status: 400,
        error: { message: 'Daily summary hour must be between 0 and 23.' },
      })),
    );

    clickButtonContaining('Save Alerts');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('must be between 0 and 23');
  });

  it('shows an error message when saving the threshold fails', async () => {
    await setup();
    profileServiceSpy.updateAlertThreshold.and.returnValue(throwError(() => new Error('server error')));

    thresholdInput().value = '750';
    thresholdInput().dispatchEvent(new Event('input'));
    await fixture.whenStable();
    clickButtonContaining('Save Threshold');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Something went wrong');
  });
});
