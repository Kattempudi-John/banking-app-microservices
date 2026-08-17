package com.example.authservice.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Marks a device a given user has already completed 2FA on, so a later login can skip the challenge.
 *
 * <p>The owner is held as a bare {@code userId} rather than a mapped {@code User} association on
 * purpose: recognizing a device is a hot path on every login, and a plain column means the check is
 * a single indexed lookup with no join or entity load behind it.
 *
 * <p>Only the hash of the device cookie is stored, never the raw {@code HttpOnly} value, so a
 * database dump cannot be replayed as a recognized device.
 */
@Entity
@Table(name = "recognized_devices")
public class RecognizedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_hash", nullable = false, unique = true)
    private String deviceHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login", nullable = false)
    private LocalDateTime lastLogin;

    /**
     * Creates an entry timestamped as of now with no owner or device hash set.
     *
     * <p>Exists for JPA. Both {@code userId} and {@code deviceHash} must be assigned before
     * persisting; the columns are non-null.
     */
    public RecognizedDevice() {
        this.createdAt = LocalDateTime.now();
        this.lastLogin = LocalDateTime.now();
    }

    /**
     * Registers a device as recognized for a user, as of now.
     *
     * @param userId the owner; not validated against the users table, so a stale id creates an
     *     entry no login will ever match
     * @param deviceHash the hash of the device cookie, never the raw cookie value; unique across
     *     all users, so the same hash cannot be recognized for two accounts
     */
    public RecognizedDevice(Long userId, String deviceHash) {
        this.userId = userId;
        this.deviceHash = deviceHash;
        this.createdAt = LocalDateTime.now();
        this.lastLogin = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getDeviceHash() { return deviceHash; }
    public void setDeviceHash(String deviceHash) { this.deviceHash = deviceHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
}