package com.example.authservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A long-lived session token that can be exchanged for a fresh access token.
 *
 * <p>Only a hash of the token is stored, so a database dump does not hand an attacker a set of
 * usable sessions; the raw value exists solely in the client's cookie.
 *
 * <p>Revocation is a soft delete — the row stays and {@code revoked} flips — so a logout leaves an
 * audit trail rather than erasing evidence. There is deliberately no setter for that flag; use
 * {@link #revoke()}, which is one-way.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Boolean revoked;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Creates a token stamped as created now, with no owner, hash, expiry, or revocation state.
     *
     * <p>Exists for JPA. Unlike the two-argument form this leaves {@code revoked} and
     * {@code expiresAt} null, so {@link #isValid()} throws on an instance built this way and not
     * fully populated.
     */
    public RefreshToken() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Issues a session token that expires 24 hours from now and starts out unrevoked.
     *
     * <p>The 24-hour lifetime is fixed here, not configurable — it is the outer bound on how long a
     * logged-out-and-forgotten session can still mint access tokens.
     *
     * @param userId the owner; carried as a plain id, not verified against the users table
     * @param tokenHash the hash of the raw token, never the raw value; unique across the table, so
     *     issuing with a hash already on file fails the insert
     */
    public RefreshToken(Long userId, String tokenHash) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.revoked = false;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusHours(24);
    }

    /**
     * Reports whether the token has passed its expiry, comparing against the clock at call time.
     *
     * <p>Says nothing about revocation; a revoked token that has not yet expired still reports
     * {@code false} here. Use {@link #isValid()} to decide whether a token may be exchanged.
     *
     * @return {@code true} once {@code expiresAt} is in the past
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * Reports whether the token may still be exchanged for an access token.
     *
     * <p>The full check a caller should use: not revoked and not expired. Evaluated against the
     * current clock, so a token can pass here and fail moments later.
     *
     * @return {@code true} only when neither revoked nor expired
     */
    public boolean isValid() {
        return !this.revoked && !isExpired();
    }

    /**
     * Marks the token as revoked in memory.
     *
     * <p>One-way: there is no un-revoke, by design. Only the in-memory field changes, so the entity
     * still has to be saved (or be managed inside a transaction) for the revocation to survive.
     */
    public void revoke() {
        this.revoked = true;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Boolean getRevoked() { return revoked; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
}