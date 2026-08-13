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

// What reading the form back returns. Same fields as ContactInfo, but every one of them is null for
// someone who has never submitted it, so it can't reuse the required-string shape the request uses.
export type ContactInfoView = { [K in keyof Required<ContactInfo>]: string | null };

export type KycStatus = 'PENDING_VERIFICATION' | 'APPROVED' | 'REJECTED';

export interface UserPreference {
  userId: number;
  alertThresholdAmount: number;
  dailySummaryEnabled: boolean;
  timezone: string;
  // Whole hour 0-23, read together with timezone above - it's the local hour in THAT zone. Used to
  // be one global setting that sent everybody's summary at 8am, so nobody could move it.
  dailySummaryHour: number;
}
