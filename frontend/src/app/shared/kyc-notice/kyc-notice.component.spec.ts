import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';

import { KycNoticeComponent } from './kyc-notice.component';
import { KycStatus } from '../../core/models/profile.models';

@Component({
  selector: 'app-kyc-notice-host',
  standalone: true,
  imports: [KycNoticeComponent],
  template: `<app-kyc-notice [status]="status()" (retry)="retryCount = retryCount + 1" />`,
})
class HostComponent {
  status = signal<KycStatus | null>(null);
  retryCount = 0;
}

describe('KycNoticeComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideRouter([])],
    });

    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate');
  });

  function render(status: KycStatus | null): void {
    host.status.set(status);
    fixture.detectChanges();
  }

  function buttons(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('button'));
  }

  it('explains what pending verification blocks and offers the verification flow', () => {
    render('PENDING_VERIFICATION');

    expect(fixture.nativeElement.textContent).toContain('Verify your identity to unlock these actions');
    expect(fixture.nativeElement.textContent).toContain('Pending verification');

    buttons().find((b) => b.textContent!.includes('Verify identity'))!.click();
    expect(router.navigate).toHaveBeenCalledWith(['/profile']);
  });

  it('tells a rejected applicant to contact support', () => {
    render('REJECTED');

    expect(fixture.nativeElement.textContent).toContain('Identity verification was declined');
    expect(fixture.nativeElement.textContent).toContain('Contact support');
  });

  // profile-service will not accept another submission once KYC is rejected, so any route back to
  // the form is a dead end
  it('offers no way back to the profile form when verification was rejected', () => {
    render('REJECTED');

    expect(fixture.nativeElement.querySelector('a[href="/profile"]')).toBeNull();
    expect(buttons().length).toBe(0);
  });

  it('reports an unknown status without claiming the actions are available', () => {
    render(null);

    expect(fixture.nativeElement.textContent).toContain("Couldn't check your verification status");
    expect(fixture.nativeElement.textContent).toContain('stay unavailable');
  });

  it('emits retry when the unknown state retry button is clicked', () => {
    render(null);

    buttons().find((b) => b.textContent!.includes('Retry'))!.click();
    expect(host.retryCount).toBe(1);
  });

  it('renders nothing once verification is approved', () => {
    render('APPROVED');

    expect(fixture.nativeElement.querySelector('.kyc-notice')).toBeNull();
  });

  // the status values are a backend contract, not customer-facing copy
  it('never shows the raw status enum', () => {
    for (const status of ['PENDING_VERIFICATION', 'REJECTED', null] as (KycStatus | null)[]) {
      render(status);

      const text: string = fixture.nativeElement.textContent;
      expect(text).not.toContain('PENDING_VERIFICATION');
      expect(text).not.toContain('REJECTED');
    }
  });
});
