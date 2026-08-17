package com.example.authservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.authservice.model.RefreshToken;

/**
 * Stores refresh tokens and revokes them in bulk when a session ends.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Finds a token by the hash of its raw value.
     *
     * <p>Lookup is by hash because the raw token is never stored; callers must hash the incoming
     * cookie with the same function before calling, or a valid token looks unknown.
     *
     * @param tokenHash the hashed token value, never the raw one
     * @return empty for an unknown token; a present result may still be revoked or expired, so
     *     callers must check {@code isValid()} before honouring it
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live refresh token belonging to one user in a single statement.
     *
     * <p>This is what makes logout end all of a user's sessions at once, rather than only the one
     * that presented a token. Already-revoked rows are excluded by the query so a repeated logout
     * does not rewrite rows or move their state; the rows are updated in place, never deleted, so
     * the audit trail survives. Expired-but-unrevoked rows are marked too, which is harmless.
     *
     * <p>Issued directly to the database: no entity is loaded, no {@code RefreshToken} already in
     * the persistence context sees the change, and the caller must supply the transaction or the
     * call fails.
     *
     * @param userId the account whose sessions end; an id with no tokens updates nothing and is not
     *     an error
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    void revokeAllUserTokens(@Param("userId") Long userId);
}