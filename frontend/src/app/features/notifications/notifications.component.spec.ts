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
});
