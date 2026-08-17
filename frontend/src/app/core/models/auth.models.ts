export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginSuccessResponse {
  status: 'SUCCESS';
  access_token: string;
}

export interface TwoFaRequiredResponse {
  status: '2FA_REQUIRED';
  // snake_case is kept verbatim from the auth-service JSON rather than camelCased here, so the
  // interface stays a literal description of the wire format and nothing has to remap it.
  pre_auth_token: string;
  // How long the emailed code stays valid. The backend owns the TTL (it can change per environment),
  // so the login screen counts down from this value instead of hard-coding three minutes.
  expires_in_seconds: number;
}

export type LoginResponse = LoginSuccessResponse | TwoFaRequiredResponse;

export interface VerifyTwoFaRequest {
  code: string;
}

// Shaped identically to TwoFaRequiredResponse today, but kept as its own type because it is a
// different endpoint's contract: resend always reissues the pre-auth token (the login one is only
// good for five minutes, so a user who waits for a second code would otherwise be left holding a
// fresh code and a dead session), and it can never return the SUCCESS variant that login can.
export interface ResendTwoFaResponse {
  status: '2FA_REQUIRED';
  pre_auth_token: string;
  expires_in_seconds: number;
}

export interface RefreshResponse {
  access_token: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  phoneNumber: string;
  // Where balance summaries and transaction alerts get delivered. Required at registration even
  // though the backend column is nullable, since older accounts predate the field.
  email: string;
}

export interface RegisterResponse {
  status: 'SUCCESS';
  message: string;
}
