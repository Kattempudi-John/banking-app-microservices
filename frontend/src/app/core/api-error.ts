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

// A 503 means the service refused to proceed because it couldn't reach a dependency - nothing was
// approved and no money moved. The backends say so in the body, but a proxy or gateway can hand
// back a 503 with no body at all, and the caller's own fallback describes the request rather than
// an unreachable dependency, so it would be misleading here. Answer for the status instead.
const SERVICE_UNAVAILABLE_MESSAGE = "We couldn't reach the service to confirm this. Please try again in a moment.";

function readServerMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }

  // A non-JSON body (a proxy error page, a network failure) leaves error.error as a string or an
  // ErrorEvent, neither of which has our keys - fall through to a fallback in that case.
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
    if (error instanceof HttpErrorResponse && error.status === 503) {
      return SERVICE_UNAVAILABLE_MESSAGE;
    }
    return fallback;
  }

  return CODE_MESSAGES[serverMessage] ?? serverMessage;
}
