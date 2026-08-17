package com.example.authservice.security;

/**
 * Marks how far through login a token's holder has got.
 *
 * <p>The name is written into the {@code token_type} claim, so these constants are part of the
 * wire format: renaming one invalidates every token already in circulation that carries the old
 * name, and {@code JwtAuthenticationFilter} rejects a token whose claim names no constant here.
 */
public enum TokenType {

    /**
     * Password accepted, 2FA still outstanding.
     *
     * <p>Lives 5 minutes, carries no {@code scope} claim, and is refused by
     * {@code JwtAuthenticationFilter} on any path outside {@code /api/v1/auth/verify-2fa}. It
     * can therefore do exactly two things: verify a code and request a new one.
     */
    PRE_AUTH,

    /**
     * Login complete, whether by clearing 2FA or by presenting a recognized device.
     *
     * <p>Lives 15 minutes and carries {@code scope=FULL_AUTH}, which the other services read as
     * the {@code SCOPE_FULL_AUTH} authority their endpoints require.
     */
    FULL_AUTH
}