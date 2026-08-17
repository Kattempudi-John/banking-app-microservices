package com.example.accountservice.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.model.AccountStatus;

/**
 * Data access for {@link AccountEntity}, the authoritative record of every account in the system.
 */
@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    /**
     * Returns a user's accounts excluding one status, used to hide closed accounts from the dashboard.
     *
     * @param userId never {@code null}; an unknown id yields an empty list rather than an error
     * @param status the status to exclude, not the one to match
     * @return matching accounts in no guaranteed order, empty when the user has none
     */
    List<AccountEntity> findByUserIdAndStatusNot(Long userId, AccountStatus status);

    /**
     * Reports whether a user already holds at least one account, of any status.
     *
     * <p>Closed accounts still count, so this answers "has this user ever been onboarded", not "does
     * this user have a usable account".
     *
     * @param userId never {@code null}
     * @return {@code true} if any row exists for the user
     */
    boolean existsByUserId(Long userId);

    /**
     * Looks up the single account holding an IBAN.
     *
     * @param iban never {@code null}; matched exactly, so casing and spacing must already be normalised
     * @return empty when no account holds it; at most one, the column is unique
     */
    Optional<AccountEntity> findByIban(String iban);

    /**
     * Looks up the single account holding a full account number.
     *
     * <p>Backs the recipient lookup behind the Transfer page's "send to someone else" mode: the
     * sender types a full account number, which is the only account identifier a recipient would
     * realistically read out to them, since the API otherwise returns it masked.
     *
     * @param accountNumber never {@code null}; must be the complete unmasked number — a masked value
     *     will simply not match
     * @return empty when no account holds it; at most one, the column is unique
     */
    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    /**
     * Returns every account still missing an IBAN.
     *
     * <p>Called once at startup by {@code IbanBackfillRunner}. Accounts created before the IBAN
     * column existed have none, and an account with no IBAN cannot be sent money on the External Wire
     * tab, so the set this returns is expected to shrink to empty and stay there.
     *
     * @return empty once the backfill has run
     */
    List<AccountEntity> findByIbanIsNull();

    /**
     * Loads an account under a pessimistic write lock held to the end of the current transaction.
     *
     * <p>This is the serialisation point for every balance change. Concurrent transfers touching the
     * same account queue here, so the read-check-write of a balance cannot be interleaved and a
     * balance check cannot be invalidated between reading it and acting on it. Callers must already
     * be inside a transaction — without one the lock is released immediately and buys nothing — and
     * must take locks in a consistent order when locking two accounts, or a transfer in each
     * direction will deadlock.
     *
     * @param id never {@code null}
     * @return empty when no such account exists, in which case no lock is taken
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Totals each user's balance across their open accounts in a single grouped query.
     *
     * <p>Answers the batch-balance lookup for notification-service without one round trip per user.
     * {@code CLOSED} accounts are excluded, so the total is spendable balance rather than historical.
     *
     * @param userIds never {@code null} or empty; an empty list produces invalid SQL on some databases
     * @return one row per user that has at least one non-closed account, each {@code Object[]} being
     *     {@code {userId, summedBalance}} — users with no open account are absent rather than zero,
     *     so callers must supply their own default
     */
    @Query("SELECT a.userId, SUM(a.availableBalance) FROM AccountEntity a " +
           "WHERE a.userId IN :userIds AND a.status <> com.example.accountservice.model.AccountStatus.CLOSED " +
           "GROUP BY a.userId")
    List<Object[]> sumAvailableBalanceByUserIds(@Param("userIds") List<Long> userIds);
}