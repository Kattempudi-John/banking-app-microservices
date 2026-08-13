export interface InternalTransferRequest {
  fromAccountId: number;
  toAccountId: number;
  amount: number;
}

// Paying a different user: the recipient is identified by their full account number rather than an
// account id, since that's the only account identifier they'd realistically pass along (the API
// only ever returns numbers masked).
export interface RecipientTransferRequest {
  fromAccountId: number;
  recipientAccountNumber: string;
  amount: number;
}

// Shown back to the sender for confirmation before any money moves. displayName is null when
// auth-service couldn't be reached - the masked number alone still identifies the account.
export interface RecipientPreview {
  maskedAccountNumber: string;
  accountType: string;
  displayName: string | null;
  // False when the recipient's own identity isn't approved. Sending to them is rejected with a 403,
  // so the sender is told at lookup time instead of after they've committed to an amount.
  verified: boolean;
}

export interface ExternalWireRequest {
  iban: string;
  swiftCode: string;
  beneficiaryName: string;
  amount: number;
}

export type TransferStatus = 'COMPLETED' | 'PENDING_APPROVAL' | 'REJECTED' | 'FAILED';

export interface TransferResponse {
  transactionId: string;
  status: TransferStatus;
  onUsTransfer: boolean;
}
