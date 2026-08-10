import { HttpErrorResponse } from '@angular/common/http';

// The backends disagree on which key carries a failure reason: transaction-service's
// GlobalExceptionHandler and auth-service's register endpoint use "error", while account-service
// and profile-service use Spring Boot's own "message" (server.error.include-message=always).
// Rather than teach every component both shapes, read either one here.
const ERROR_KEYS = ['error', 'message'] as const;

// Backends raise a handful of machine-readable codes instead of prose, so the API stays
// client-agnostic. Translate those to something a person can act on; anything else falls through
// to whatever the server actually said, which is almost always more useful than a generic line.
const CODE_MESSAGES: Record<string, string> = {
  INSUFFICIENT_FUNDS: "You don't have enough available balance in that account.",
  SAME_ACCOUNT: "You can't transfer to the same account you're sending from. Pick a different destination.",
};

function readServerMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }

  // A non-JSON body (a proxy error page, a network failure) leaves error.error as a string or an
  // ErrorEvent, neither of which has our keys - fall through to the caller's fallback in that case.
  const payload = error.error;
  if (!payload || typeof payload !== 'object') {
    return null;
  }

  for (const key of ERROR_KEYS) {
    const value = (payload as Record<string, unknown>)[key];
    if (typeof value === 'string' && value.trim().length > 0) {
      return value;
    }
  }

  return null;
}

export function extractApiError(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  const serverMessage = readServerMessage(error);

  if (serverMessage === null) {
    return fallback;
  }

  return CODE_MESSAGES[serverMessage] ?? serverMessage;
}
