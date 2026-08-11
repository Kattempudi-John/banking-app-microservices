// The notification feed stores whatever was actually dispatched, and the email-backed types
// (TRANSACTION_ALERT, DAILY_SUMMARY) are built as full HTML documents by text blocks in
// notification-service. The table renders every cell as text, so those rows arrived on screen as
// literal "<html> <body> <h2>Transaction Alert</h2>..." markup.
//
// Converting for display rather than changing what is stored: the stored message is the record of
// what was really sent, and rewriting it to suit one table would throw that audit trail away.

// DOMParser is used instead of assigning to innerHTML on a detached element. Both give correct
// entity decoding, but a parsed document is inert - no <img onerror>, no resource fetches, nothing
// executes - and these bodies ultimately originate from server-side templates, so the safer of two
// equivalent options is the right default.
function htmlToText(html: string): string {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  return parsed.body.textContent ?? '';
}

// Only worth parsing when the value actually looks like markup - the SMS and profile-security
// messages are already plain sentences and should pass through untouched.
const LOOKS_LIKE_HTML = /<[a-z!/][^>]*>/i;

/**
 * Renders a stored notification body as the single readable line a table cell needs.
 * Plain-text messages are returned unchanged apart from whitespace tidying.
 */
export function toReadableMessage(message: string | null | undefined): string {
  if (!message) {
    return '';
  }

  const text = LOOKS_LIKE_HTML.test(message) ? htmlToText(message) : message;

  // The text blocks are indented for readability in the Java source, so the extracted text carries
  // newlines and long runs of spaces that would otherwise show up as gaps mid-sentence.
  return text.replace(/\s+/g, ' ').trim();
}
