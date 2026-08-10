import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  ExternalWireRequest,
  InternalTransferRequest,
  RecipientPreview,
  RecipientTransferRequest,
  TransferResponse,
} from '../models/transfer.models';
import { HistoryStatus, WireTransferPage } from '../models/history.models';

export interface TransferHistoryQuery {
  accountId?: number;
  status?: HistoryStatus;
  from?: string;
  to?: string;
  page?: number;
}

@Injectable({ providedIn: 'root' })
export class TransferService {
  private readonly baseUrl = environment.transactionApiUrl;

  constructor(private readonly http: HttpClient) {}

  transferInternal(request: InternalTransferRequest): Observable<TransferResponse> {
    return this.http.post<TransferResponse>(`${this.baseUrl}/internal`, request);
  }

  transferToRecipient(request: RecipientTransferRequest): Observable<TransferResponse> {
    return this.http.post<TransferResponse>(`${this.baseUrl}/to-recipient`, request);
  }

  // Called as the sender finishes typing a recipient account number, so they can confirm the name
  // before sending. Read-only - nothing moves until transferToRecipient above is called.
  previewRecipient(accountNumber: string): Observable<RecipientPreview> {
    return this.http.get<RecipientPreview>(`${this.baseUrl}/recipients/${accountNumber}`);
  }

  transferExternal(fromAccountId: number, request: ExternalWireRequest): Observable<TransferResponse> {
    const params = new HttpParams().set('fromAccountId', fromAccountId);
    return this.http.post<TransferResponse>(`${this.baseUrl}/external`, request, { params });
  }

  // Powers the History page's wire half (external/on-us wires, which carry approval status the
  // ledger-sourced entries from AccountService don't have).
  getTransferHistory(query: TransferHistoryQuery): Observable<WireTransferPage> {
    let params = new HttpParams();
    if (query.accountId !== undefined) {
      params = params.set('accountId', query.accountId);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }

    return this.http.get<WireTransferPage>(this.baseUrl, { params });
  }
}
