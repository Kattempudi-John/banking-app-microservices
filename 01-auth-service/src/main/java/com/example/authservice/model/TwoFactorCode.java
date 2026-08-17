package com.example.authservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "two_factor_codes")
public class TwoFactorCode {

    // Mirrors the `:180` fallback on application.security.two-factor.code-ttl-seconds so the
    // no-config default is the same number whichever way a code gets constructed.
    public static final long DEFAULT_TTL_SECONDS = 180;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // The hashed version of the 6-digit code
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // --- Constructors ---

    public TwoFactorCode() {
        this.createdAt = LocalDateTime.now();
    }

    // The lifetime the UI counts down from has to be the same number this row expires on, so the
    // TTL is passed in from the one configured property rather than hardcoded here - a literal in
    // this constructor is exactly how the old 5 minutes drifted out of sync with everything else.
    public TwoFactorCode(Long userId, String codeHash, long ttlSeconds) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.attempts = 0;
        // One LocalDateTime.now() for both columns, not two calls - reading the clock twice lets a
        // millisecond slip between created_at and expires_at, and the resend cooldown measures from
        // created_at while the countdown measures to expires_at.
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(ttlSeconds);
    }

    // Kept so callers that don't care about the lifetime (tests, mostly) still compile; it just
    // delegates with the same 180 second default every @Value reading the property falls back to.
    public TwoFactorCode(Long userId, String codeHash) {
        this(userId, codeHash, DEFAULT_TTL_SECONDS);
    }

    // --- Rich Domain Helper Methods ---

    // this kind of method living right on the entity is sometimes called a rich domain model,
    // the expiry logic lives with the data it operates on instead of being scattered into a service
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    // --- Getters and Setters ---

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