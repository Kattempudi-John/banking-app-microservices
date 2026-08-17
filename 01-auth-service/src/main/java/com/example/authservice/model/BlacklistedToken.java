package com.example.authservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Records a JWT that has been revoked before its own expiry, so the filter chain can reject it.
 *
 * <p>The primary key is the token's own {@code jti} claim rather than a generated id: it is
 * already a globally unique UUID, which lets a revocation check be a primary-key lookup instead of
 * a query, and makes inserting the same {@code jti} twice a no-op collision rather than a duplicate
 * row.
 *
 * <p>Rows are not permanent. {@code expiresAt} carries the revoked token's own expiry, and the
 * hourly purge deletes everything past it — once the token would have expired anyway, the blacklist
 * entry has no work left to do.
 */
@Entity
@Table(name = "revoked_jwt_blacklist")
public class BlacklistedToken {

    @Id
    @Column(name = "jti", length = 36, nullable = false)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt;

    /**
     * Creates an entry with the revocation timestamp already stamped and no {@code jti} yet.
     *
     * <p>Exists for JPA, which needs a no-argument constructor to materialize a row. Persisting an
     * instance built this way without setting {@code jti} violates the primary key.
     */
    public BlacklistedToken() {
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * Creates a revocation entry for a token, stamping the revocation time as now.
     *
     * @param jti the token's {@code jti} claim, never {@code null}; re-revoking the same token
     *     collides on the primary key rather than inserting twice
     * @param expiresAt the revoked token's own expiry, not a retention period; the hourly purge
     *     deletes the row once this passes, so a value in the past makes the entry vanish before it
     *     ever blocks a request
     */
    public BlacklistedToken(String jti, LocalDateTime expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.revokedAt = LocalDateTime.now();
    }

    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
}