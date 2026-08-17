package com.example.authservice.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.example.authservice.model.BlacklistedToken;

/**
 * Stores revoked JWTs and answers whether a presented token has been revoked.
 *
 * <p>The key type is {@code String} because the entity is keyed by the token's {@code jti} claim,
 * which means the revocation check on every authenticated request is the inherited
 * {@code existsById} — a primary-key hit, not a query.
 */
@Repository
public interface BlacklistedTokenRepository extends JpaRepository<BlacklistedToken, String> {

    /**
     * Deletes every blacklist entry whose revoked token has already expired on its own.
     *
     * <p>Bulk delete issued straight to the database, so it does not load entities and any already
     * loaded in the persistence context are left stale. Callers must supply the transaction — this
     * modifies rows, and without an active transaction the call fails rather than silently doing
     * nothing.
     *
     * <p>Safe to run repeatedly and safe to skip: it only removes entries a token's own expiry
     * would have made irrelevant, so a missed run costs table growth, never a token slipping past
     * revocation. Skipping it entirely is what lets the table grow without bound.
     *
     * @param now the cutoff; entries expiring strictly before it are removed, so passing a future
     *     time deletes entries that are still doing work
     */
    @Modifying
    @Query("DELETE FROM BlacklistedToken b WHERE b.expiresAt < :now")
    void deleteAllExpiredTokensSince(LocalDateTime now);
}