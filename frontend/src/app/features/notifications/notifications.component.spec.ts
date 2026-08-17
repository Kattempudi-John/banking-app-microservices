import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { NotificationsComponent } from './notifications.component';
import { NotificationService } from '../../core/services/notification.service';
import { NotificationPage } from '../../core/models/notification.models';
import { AuthService } from '../../core/auth.service';

describe('NotificationsComponent', () => {
  let fixture: ComponentFixture<NotificationsComponent>;
  let notificationServiceSpy: jasmine.SpyObj<NotificationService>;

  const onePage: NotificationPage = {
    content: [
      { id: 1, type: 'SMS_2FA', channel: 'SMS', subject: null, message: 'Verification code sent to ***4567.', status: 'SENT', createdAt: '2026-08-01T10:00:00Z' },
      { id: 2, type: 'TRANSACTION_ALERT', channel: 'EMAIL', subject: 'Bank Alert', message: 'Large debit detected.', status: 'SENT', createdAt: '2026-07-31T09:00:00Z' },
    ],
    totalPages: 1,
    totalElements: 2,
    number: 0,
    size: 50,
  };

  function setup(page: NotificationPage = onePage): void {
    notificationServiceSpy = jasmine.createSpyObj('NotificationService', ['getNotifications']);
    notificationServiceSpy.getNotifications.and.returnValue(of(page));

    TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [
        provideRouter([]),
        { provide: NotificationService, useValue: notificationServiceSpy },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }) },
      ],
    });

    fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
  }

  // Selects and date inputs both report through their change event, so one helper drives either.
  function setFilter(selector: string, value: string): void {
    const control: HTMLSelectElement | HTMLInputElement = fixture.nativeElement.querySelector(selector);
    control.value = value;
    control.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function clickButton(label: string): void {
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.includes(label))!.click();
    fixture.detectChanges();
  }

  it('loads notifications on init', () => {
    setup();
    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0);
  });

  it('renders each notification with its type, channel, message, and status', () => {
    setup();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('SMS_2FA');
    expect(text).toContain('Verification code sent to ***4567.');
    expect(text).toContain('TRANSACTION_ALERT');
    expect(text).toContain('SENT');
  });

  it('renders an HTML email body as readable text, not raw markup', () => {
    setup({
      ...onePage,
      content: [
        {
          id: 3,
          type: 'DAILY_SUMMARY',
          channel: 'EMAIL',
          subject: 'Your Daily Balance Summary',
          message: '<html>\n  <body>\n    <h2>Good Morning!</h2>\n    <div style="color: #2E86C1;">Total Aggregate Balance: $20000.0000</div>\n  </body>\n</html>',
          status: 'SENT',
          createdAt: '2026-08-01T10:00:00Z',
        },
      ],
    });

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Good Morning!');
    expect(text).toContain('Total Aggregate Balance: $20000.0000');
    expect(text).not.toContain('<h2>');
    expect(text).not.toContain('<html>');
    expect(text).not.toContain('2E86C1');
  });

  it('formats the raw timestamp instead of printing it verbatim', () => {
    setup();
    const text = fixture.nativeElement.textContent;
    // The API sends a bare LocalDateTime; the microsecond-precision ISO string should not survive.
    expect(text).not.toContain('2026-08-01T10:00:00Z');
  });

  it('shows an empty state when there are no notifications', () => {
    setup({ content: [], totalPages: 0, totalElements: 0, number: 0, size: 50 });
    expect(fixture.nativeElement.textContent).toContain('No notifications yet');
  });

  it('shows an error state with a retry button when loading fails', () => {
    notificationServiceSpy = jasmine.createSpyObj('NotificationService', ['getNotifications']);
    notificationServiceSpy.getNotifications.and.returnValue(throwError(() => new Error('network error')));

    TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [
        provideRouter([]),
        { provide: NotificationService, useValue: notificationServiceSpy },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', { logout: of({}) }) },
      ],
    });
    fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Unable to load your notifications');
  });

  it('shows pagination controls and re-fetches the next page on Next click', () => {
    setup({ ...onePage, totalPages: 4, number: 0 });
    expect(fixture.nativeElement.textContent).toContain('Page 1 of 4');

    notificationServiceSpy.getNotifications.calls.reset();
    notificationServiceSpy.getNotifications.and.returnValue(of({ ...onePage, totalPages: 4, number: 1 }));

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    const nextButton = buttons.find((b) => b.textContent?.includes('Next'))!;
    nextButton.click();

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(1);
  });

  it('re-fetches the first page with the chosen type when the type filter changes', () => {
    setup();
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#typeFilter', 'DAILY_SUMMARY');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0, { type: 'DAILY_SUMMARY' });
  });

  it('offers EMAIL_2FA as a type filter, since 2FA codes are emailed now', () => {
    // The option values are the API filter contract, not display labels - they have to match
    // notification-service's enum exactly. SMS_2FA stays because records written before the switch
    // are still served by the feed and still have to be reachable.
    setup();

    const values = Array.from<HTMLOptionElement>(
      fixture.nativeElement.querySelectorAll('#typeFilter option'),
    ).map((option) => option.value);

    expect(values).toContain('EMAIL_2FA');
    expect(values).toContain('SMS_2FA');
  });

  it('re-fetches the first page with EMAIL_2FA when that type is chosen', () => {
    setup();
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#typeFilter', 'EMAIL_2FA');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0, { type: 'EMAIL_2FA' });
  });

  it('sends every selected filter together rather than only the last one touched', () => {
    setup();
    setFilter('#typeFilter', 'TRANSACTION_ALERT');
    setFilter('#channelFilter', 'EMAIL');
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#statusFilter', 'FAILED');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0, {
      type: 'TRANSACTION_ALERT',
      channel: 'EMAIL',
      status: 'FAILED',
    });
  });

  it('sends a from date from the start of that day', () => {
    setup();
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#fromFilter', '2026-08-01');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0, { from: '2026-08-01T00:00:00' });
  });

  it('stretches a to date to the end of that day so same-day notifications still match', () => {
    setup();
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#toFilter', '2026-08-05');

    const [, filters] = notificationServiceSpy.getNotifications.calls.mostRecent().args;
    expect(filters?.to).toBe('2026-08-05T23:59:59');
    // A bound of midnight would exclude everything that happened during the day the user picked.
    const lateThatDay = new Date('2026-08-05T18:30:00').getTime();
    expect(new Date(filters!.to!).getTime()).toBeGreaterThan(lateThatDay);
  });

  it('goes back to the first page when a filter changes on a later page', () => {
    setup({ ...onePage, totalPages: 4, number: 0 });
    notificationServiceSpy.getNotifications.and.returnValue(of({ ...onePage, totalPages: 4, number: 1 }));
    clickButton('Next');
    notificationServiceSpy.getNotifications.calls.reset();

    setFilter('#statusFilter', 'SENT');

    // Page 1 could easily be past the end of a narrower result set, which reads as data loss.
    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0, { status: 'SENT' });
  });

  it('keeps the active filters when paging', () => {
    setup({ ...onePage, totalPages: 4, number: 0 });
    setFilter('#typeFilter', 'SMS_2FA');
    notificationServiceSpy.getNotifications.and.returnValue(of({ ...onePage, totalPages: 4, number: 1 }));
    notificationServiceSpy.getNotifications.calls.reset();

    clickButton('Next');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(1, { type: 'SMS_2FA' });
  });

  it('restores the unfiltered request when the filters are cleared', () => {
    setup();
    setFilter('#typeFilter', 'SMS_2FA');
    setFilter('#channelFilter', 'SMS');
    notificationServiceSpy.getNotifications.calls.reset();

    clickButton('Clear filters');

    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0);
    const typeSelect: HTMLSelectElement = fixture.nativeElement.querySelector('#typeFilter');
    const channelSelect: HTMLSelectElement = fixture.nativeElement.querySelector('#channelFilter');
    expect(typeSelect.value).toBe('');
    expect(channelSelect.value).toBe('');
  });

  it('sends no filter params at all when nothing is selected', () => {
    setup();
    expect(notificationServiceSpy.getNotifications).toHaveBeenCalledWith(0);
    expect(notificationServiceSpy.getNotifications.calls.mostRecent().args.length).toBe(1);
  });

  it('reads a filter combination that matches nothing as an empty result, not an error', () => {
    setup();
    notificationServiceSpy.getNotifications.and.returnValue(
      of({ content: [], totalPages: 0, totalElements: 0, number: 0, size: 50 }),
    );

    setFilter('#typeFilter', 'PROFILE_SECURITY');

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('No notifications match these filters');
    expect(text).not.toContain('Unable to load your notifications');
  });
});
