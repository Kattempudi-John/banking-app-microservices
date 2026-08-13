import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Router } from '@angular/router';

import { KycStatus } from '../../core/models/profile.models';
import { ButtonComponent } from '../button/button.component';

type NoticeVariant = 'pending' | 'rejected' | 'unknown';

interface NoticeCopy {
  pill: string | null;
  title: string;
  body: string;
}

// Copy lives here rather than in the template so the raw KycStatus enum never reaches the DOM -
// 'PENDING_VERIFICATION' is a backend contract value, not something to show a customer.
const NOTICE_COPY: Record<NoticeVariant, NoticeCopy> = {
  pending: {
    pill: 'Pending verification',
    title: 'Verify your identity to unlock these actions',
    body:
      'Adding funds and opening a new account both need a verified identity. ' +
      'It takes about a minute to complete.',
  },
  rejected: {
    pill: 'Rejected',
    title: 'Identity verification was declined',
    // profile-service refuses a resubmission once KYC is REJECTED, so pointing this user at the
    // form would send them somewhere that can only fail again.
    body:
      'This cannot be cleared by resubmitting the form. ' +
      'Contact support to have your verification reviewed.',
  },
  unknown: {
    pill: null,
    title: "Couldn't check your verification status",
    body: 'Adding funds and opening a new account stay unavailable until we can confirm your verification.',
  },
};

@Component({
  selector: 'app-kyc-notice',
  standalone: true,
  imports: [ButtonComponent],
  template: `
    @if (copy; as notice) {
      <div class="kyc-notice" [class]="'kyc-notice kyc-notice-' + variant" role="status">
        <span class="kyc-notice-icon" aria-hidden="true">
          @switch (variant) {
            @case ('pending') {
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M12 7v5l3 2" />
              </svg>
            }
            @case ('rejected') {
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="12" r="9" />
                <path d="m15 9-6 6" />
                <path d="m9 9 6 6" />
              </svg>
            }
            @default {
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M9.5 9.5a2.5 2.5 0 1 1 3 2.4v1.1" />
                <path d="M12.5 16.5h.01" />
              </svg>
            }
          }
        </span>
        <div class="kyc-notice-content">
          @if (notice.pill) {
            <span class="kyc-notice-pill">{{ notice.pill }}</span>
          }
          <h2 class="kyc-notice-title">{{ notice.title }}</h2>
          <p class="kyc-notice-body">{{ notice.body }}</p>
          @if (variant === 'pending') {
            <app-button (clicked)="verifyIdentity()">Verify identity</app-button>
          }
          @if (variant === 'unknown') {
            <app-button variant="secondary" (clicked)="retry.emit()">Retry</app-button>
          }
        </div>
      </div>
    }
  `,
  styleUrl: './kyc-notice.component.css',
})
export class KycNoticeComponent {
  // null covers a failed status lookup, which is a different message from any real status.
  @Input() status: KycStatus | null = null;
  @Output() retry = new EventEmitter<void>();

  constructor(private readonly router: Router) {}

  get variant(): NoticeVariant | null {
    switch (this.status) {
      // An approved user is not blocked from anything, so there is nothing to warn about.
      case 'APPROVED':
        return null;
      case 'PENDING_VERIFICATION':
        return 'pending';
      case 'REJECTED':
        return 'rejected';
      default:
        return 'unknown';
    }
  }

  get copy(): NoticeCopy | null {
    const variant = this.variant;
    return variant ? NOTICE_COPY[variant] : null;
  }

  verifyIdentity(): void {
    this.router.navigate(['/profile']);
  }
}
