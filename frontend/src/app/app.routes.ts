import { Routes } from '@angular/router';

import { LoginComponent } from './features/login/login.component';
import { SignupComponent } from './features/signup/signup.component';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { AccountTransactionsComponent } from './features/account-transactions/account-transactions.component';
import { TransferComponent } from './features/transfer/transfer.component';
import { ProfileComponent } from './features/profile/profile.component';
import { AlertPreferencesComponent } from './features/alert-preferences/alert-preferences.component';
import { NotificationsComponent } from './features/notifications/notifications.component';
import { HistoryComponent } from './features/history/history.component';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'signup', component: SignupComponent },
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'accounts/:id/transactions', component: AccountTransactionsComponent, canActivate: [authGuard] },
  { path: 'transfer', component: TransferComponent, canActivate: [authGuard] },
  { path: 'profile', component: ProfileComponent, canActivate: [authGuard] },
  { path: 'profile/alerts', component: AlertPreferencesComponent, canActivate: [authGuard] },
  { path: 'notifications', component: NotificationsComponent, canActivate: [authGuard] },
  { path: 'history', component: HistoryComponent, canActivate: [authGuard] },
  { path: '', redirectTo: 'login', pathMatch: 'full' },
];
