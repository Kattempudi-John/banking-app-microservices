// Mirrors notification-service's NotificationType, which is backed by a real PostgreSQL enum - these
// strings are the API filter contract, so they have to match it exactly rather than read nicely.
// SMS_2FA stays alongside EMAIL_2FA: 2FA codes are emailed now, but records written before that
// change are still served by GET /api/v1/notifications and still have to be filterable.
export type NotificationType =
  | 'EMAIL_2FA'
  | 'SMS_2FA'
  | 'TRANSACTION_ALERT'
  | 'PROFILE_SECURITY'
  | 'DAILY_SUMMARY';
export type NotificationChannel = 'SMS' | 'EMAIL';
export type NotificationDeliveryStatus = 'SENT' | 'FAILED';

export interface Notification {
  id: number;
  type: NotificationType;
  channel: NotificationChannel;
  subject: string | null;
  message: string;
  status: NotificationDeliveryStatus;
  createdAt: string;
}

export interface NotificationPage {
  content: Notification[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}
