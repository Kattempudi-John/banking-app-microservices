import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AccountOverview, AccountType, TransactionPage, TransactionType } from '../models/account.models';

export interface TransactionQuery {
  type?: TransactionType;
  page?: number;
}

export interface AllTransactionsQuery {
  accountId?: number;
  type?: TransactionType;
  from?: string;
  to?: string;
  page?: number;
}

@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly baseUrl = environment.accountApiUrl;

  constructor(private readonly http: HttpClient) {}

  getAccounts(): Observable<AccountOverview[]> {
    return this.http.get<AccountOverview[]>(this.baseUrl);
  }

  getTransactions(accountId: number, query: TransactionQuery): Observable<TransactionPage> {
    let params = new HttpParams();
    if (query.type) {
      params = params.set('type', query.type);
    }
    if (query.page !== undefined) {
      params = params.set('page', query.page);
    }

    return this.http.get<TransactionPage>(`${this.baseUrl}/${accountId}/transactions`, { params });
  }

  // Powers the History page's ledger half (deposits, internal-transfer legs) - spans every
  // account the caller owns instead of the single-account scope getTransactions above is limited to.
  getAllTransactions(query: AllTransactionsQuery): Observable<TransactionPage> {
    let params = new HttpParams();
    if (query.accountId !== undefined) {
      params = params.set('accountId', query.accountId);
    }
    if (query.type) {
      params = params.set('type', query.type);
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

    return this.http.get<TransactionPage>(`${this.baseUrl}/transactions`, { params });
  }

  depositFunds(accountId: number, amount: number): Observable<AccountOverview> {
    return this.http.post<AccountOverview>(`${this.baseUrl}/${accountId}/deposit`, { amount });
  }

  openAccount(accountType: AccountType): Observable<AccountOverview> {
    return this.http.post<AccountOverview>(this.baseUrl, { accountType });
  }

  seedDemoTransactions(accountId: number): Observable<AccountOverview> {
    return this.http.post<AccountOverview>(`${this.baseUrl}/${accountId}/demo-transactions`, {});
  }
}
