package com.example.authservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A pending one-time 2FA challenge for a user, stored as a hash with an expiry and an attempt
 * count.
 *
 * <p>Only the hash of the six-digit code is persisted, so the code itself cannot be read back out
 * of the database and replayed.
 *
 * <p>{@code createdAt} and {@code expiresAt} are both meaningful to callers and are not
 * interchangeable: the resend cooldown measures forward from {@code createdAt} while the on-screen
 * countdown measures toward {@code expiresAt}. Both are set from a single clock reading so they can
 * never drift apart by the millisecond it takes to read the clock twice.
 */
@Entity
@Table(name = "two_factor_codes")
public class TwoFactorCode {

    /**
     * Code lifetime in seconds used when no explicit TTL is supplied.
     *
     * <p>Deliberately the same number as the {@code :180} fallback on
     * {@code application.security.two-factor.code-ttl-seconds}, so a code expires after the same
     * interval however it was constructed. Change one and the other must change with it.
     */
    public static final long DEFAULT_TTL_SECONDS = 180;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Creates a challenge stamped as created now, with no owner, hash, attempt count, or expiry.
     *
     * <p>Exists for JPA. {@code expiresAt} is left null, so {@link #isExpired()} throws on an
     * instance built this way and not fully populated.
     */
    public TwoFactorCode() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Issues a challenge that expires {@code ttlSeconds} from now with zero attempts recorded.
     *
     * <p>The lifetime is a parameter rather than a literal so that the row's expiry, the
     * {@code expires_in_seconds} the login response reports, and the countdown the UI renders all
     * come from the one configured property. Hardcoding it here is exactly how an earlier
     * five-minute value drifted away from what the rest of the flow believed.
     *
     * @param userId the owner; a user may only have one live challenge, so callers delete any
     *     existing row for this id before inserting
     * @param codeHash the hash of the six-digit code, never the code itself
     * @param ttlSeconds seconds from now until the code stops being accepted; should come from
     *     {@code application.security.two-factor.code-ttl-seconds}, and a non-positive value
     *     produces a code that is already expired
     */
    public TwoFactorCode(Long userId, String codeHash, long ttlSeconds) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.attempts = 0;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(ttlSeconds);
    }

    /**
     * Issues a challenge using the built-in {@link #DEFAULT_TTL_SECONDS} lifetime.
     *
     * <p>For callers that have no configured TTL to hand, chiefly tests. Production code should use
     * the three-argument form and pass the configured property, because this default is only kept
     * in step with it by convention.
     *
     * @param userId the owner; a user may only have one live challenge at a time
     * @param codeHash the hash of the six-digit code, never the code itself
     */
    public TwoFactorCode(Long userId, String codeHash) {
        this(userId, codeHash, DEFAULT_TTL_SECONDS);
    }

    /**
     * Reports whether the code is past its expiry, comparing against the clock at call time.
     *
     * <p>Expiry alone; it says nothing about how many attempts have been spent, so a caller
     * enforcing an attempt limit must check {@code attempts} separately.
     *
     * @return {@code true} once {@code expiresAt} is in the past
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * Records one more failed verification attempt against this code.
     *
     * <p>Changes only the in-memory field, so the entity must be saved (or be managed inside a
     * transaction) for the increment to count — otherwise a brute-force attempt is never actually
     * limited.
     */
    public void incrementAttempts() {
        this.attempts++;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}