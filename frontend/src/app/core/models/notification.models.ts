export type NotificationType = 'SMS_2FA' | 'TRANSACTION_ALERT' | 'PROFILE_SECURITY' | 'DAILY_SUMMARY';
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
