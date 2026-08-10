// CREDIT/DEBIT come from account-service's ledger (deposits and internal-transfer legs both land
// there with no further distinction available); ON_US_WIRE/EXTERNAL_WIRE come from
// transaction-service's wire records, split by whether destinationAccountId is set.
export type HistoryKind = 'CREDIT' | 'DEBIT' | 'ON_US_WIRE' | 'EXTERNAL_WIRE';
export type HistoryStatus = 'COMPLETED' | 'PENDING_APPROVAL' | 'REJECTED' | 'FAILED';

// The shape the History page renders - built client-side by merging AccountService's ledger
// transactions with TransferService's wire records (see HistoryComponent), not returned by any
// single backend endpoint.
export interface HistoryEntry {
  key: string;
  createdAt: string;
  kind: HistoryKind;
  accountId: number;
  description: string;
  amount: number;
  status: HistoryStatus;
}

export interface WireTransferRecord {
  transactionId: string;
  accountId: number;
  amount: number;
  status: HistoryStatus;
  description: string;
  iban: string | null;
  swiftCode: string | null;
  beneficiaryName: string | null;
  destinationAccountId: number | null;
  createdAt: string;
}

export interface WireTransferPage {
  content: WireTransferRecord[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}
