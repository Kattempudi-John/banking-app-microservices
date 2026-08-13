import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { NotificationService } from './notification.service';
import { environment } from '../../../environments/environment';
import { NotificationPage } from '../models/notification.models';

describe('NotificationService', () => {
  let service: NotificationService;
  let httpMock: HttpTestingController;

  const emptyPage: NotificationPage = { content: [], totalPages: 0, totalElements: 0, number: 0, size: 50 };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [NotificationService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NotificationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('requests a page of notifications', () => {
    let result: NotificationPage | undefined;

    service.getNotifications(2).subscribe((res) => (result = res));

    const req = httpMock.expectOne((r) => r.url === environment.notificationApiUrl && r.params.get('page') === '2');
    expect(req.request.method).toBe('GET');
    req.flush(emptyPage);

    expect(result).toEqual(emptyPage);
  });

  it('sends only the filters that are set', () => {
    service.getNotifications(0, { type: 'DAILY_SUMMARY', status: 'FAILED' }).subscribe();

    const req = httpMock.expectOne((r) => r.url === environment.notificationApiUrl);
    expect(req.request.params.get('type')).toBe('DAILY_SUMMARY');
    expect(req.request.params.get('status')).toBe('FAILED');
    // An unset filter must be absent rather than an empty value the API would try to match against.
    expect(req.request.params.has('channel')).toBe(false);
    expect(req.request.params.has('from')).toBe(false);
    expect(req.request.params.has('to')).toBe(false);
    req.flush(emptyPage);
  });

  it('sends every filter together when all of them are set', () => {
    service
      .getNotifications(0, {
        type: 'TRANSACTION_ALERT',
        channel: 'EMAIL',
        status: 'SENT',
        from: '2026-08-01T00:00:00',
        to: '2026-08-05T23:59:59',
      })
      .subscribe();

    const req = httpMock.expectOne((r) => r.url === environment.notificationApiUrl);
    expect(req.request.params.get('type')).toBe('TRANSACTION_ALERT');
    expect(req.request.params.get('channel')).toBe('EMAIL');
    expect(req.request.params.get('status')).toBe('SENT');
    expect(req.request.params.get('from')).toBe('2026-08-01T00:00:00');
    expect(req.request.params.get('to')).toBe('2026-08-05T23:59:59');
    req.flush(emptyPage);
  });

  it('sends no filter params when called without filters', () => {
    service.getNotifications(0).subscribe();

    const req = httpMock.expectOne((r) => r.url === environment.notificationApiUrl);
    expect(req.request.params.keys()).toEqual(['page']);
    req.flush(emptyPage);
  });
});
