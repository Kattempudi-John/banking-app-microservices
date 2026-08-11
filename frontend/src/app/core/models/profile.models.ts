export interface ContactInfo {
  // Identity fields - submitting these is what triggers KYC verification, so they're required
  // alongside the contact details rather than optional extras.
  legalName: string;
  // ISO yyyy-MM-dd, straight from <input type="date">, which is the format the backend's
  // @JsonFormat expects.
  dateOfBirth: string;
  phoneNumber: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state: string;
  zipCode: string;
}

export type KycStatus = 'PENDING_VERIFICATION' | 'APPROVED' | 'REJECTED';

export interface UserPreference {
  userId: number;
  alertThresholdAmount: number;
  dailySummaryEnabled: boolean;
  timezone: string;
}
