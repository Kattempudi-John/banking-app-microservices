import { toReadableMessage } from './notification-message';

describe('toReadableMessage', () => {
  // The exact shape DailyBalanceSummaryJob builds with a Java text block - a full HTML document,
  // indented in the source, which the notifications table used to print verbatim as markup.
  const dailySummaryHtml = `
    <html>
        <body>
            <h2>Good Morning!</h2>
            <p>Here is your daily aggregate balance summary:</p>
            <div style="font-size: 24px; font-weight: bold; color: #2E86C1;">
                Total Aggregate Balance: $20000.0000
            </div>
            <p>Thank you for banking with us.</p>
        </body>
    </html>
  `;

  it('strips the markup out of an HTML email body', () => {
    const result = toReadableMessage(dailySummaryHtml);

    expect(result).not.toContain('<');
    expect(result).not.toContain('html');
    // The style attribute is markup, not content - none of it should survive into the cell.
    expect(result).not.toContain('2E86C1');
    expect(result).toContain('Good Morning!');
    expect(result).toContain('Total Aggregate Balance: $20000.0000');
  });

  it('collapses the source indentation into single spaces', () => {
    const result = toReadableMessage(dailySummaryHtml);

    expect(result).not.toMatch(/\s{2,}/);
    expect(result).not.toContain('\n');
    expect(result.startsWith('Good Morning!')).toBeTrue();
  });

  it('leaves an already-plain message untouched', () => {
    expect(toReadableMessage('Your verification code is 123456. It expires in 5 minutes.')).toBe(
      'Your verification code is 123456. It expires in 5 minutes.',
    );
  });

  it('keeps a plain message containing comparison operators intact', () => {
    // Guards the "does this look like HTML" check against treating arithmetic as markup.
    expect(toReadableMessage('Balance < 100 and transfers > 5 were flagged')).toBe(
      'Balance < 100 and transfers > 5 were flagged',
    );
  });

  it('decodes HTML entities rather than showing their source form', () => {
    expect(toReadableMessage('<p>Tom &amp; Jerry&#39;s account</p>')).toBe("Tom & Jerry's account");
  });

  it('returns an empty string for a null or empty message', () => {
    expect(toReadableMessage(null)).toBe('');
    expect(toReadableMessage(undefined)).toBe('');
    expect(toReadableMessage('')).toBe('');
  });

  it('does not execute or retain injected script content', () => {
    const result = toReadableMessage('<p>Hello</p><script>window.pwned = true;</script>');

    expect((window as unknown as Record<string, unknown>)['pwned']).toBeUndefined();
    expect(result).not.toContain('<script>');
  });
});
