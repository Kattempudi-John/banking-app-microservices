package com.example.authservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import com.example.authservice.model.TwoFactorCode;

/**
 * Stores the single pending 2FA challenge per user.
 *
 * <p>The flow depends on there being at most one live row per user: issuing a new code deletes any
 * existing one first, which is what stops a previously sent code from remaining valid alongside it.
 */
@Repository
public interface TwoFactorCodeRepository extends JpaRepository<TwoFactorCode, Long> {

    /**
     * Finds the user's pending challenge.
     *
     * <p>Returns a single result on the assumption that only one row per user exists at a time; if
     * that invariant is ever broken, this throws rather than picking one.
     *
     * @param userId the account being challenged
     * @return empty when no code has been issued or the previous one was consumed; the returned
     *     code may still be expired or out of attempts, which callers must check
     */
    Optional<TwoFactorCode> findByUserId(Long userId);

    /**
     * Removes any pending challenge for a user.
     *
     * <p>Called before issuing a new code so the old one cannot also be redeemed, and after a
     * successful verification so a code is genuinely one-time. Deleting nothing is the normal case
     * for a first-time challenge, not an error. Requires a transaction from the caller.
     *
     * @param userId the account whose pending code is discarded
     */
    @Modifying
    void deleteByUserId(Long userId);
}