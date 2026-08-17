package com.example.accountservice.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.accountservice.model.TransactionEntity;
import com.example.accountservice.model.TransactionType;

/**
 * Data access for {@link TransactionEntity}, the append-only ledger of balance movements.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    /**
     * Returns one page of an account's transactions.
     *
     * @param accountId never {@code null}; not ownership-checked here, the caller must have done that
     * @param pageable supplies limit, offset and sort; without an explicit sort the row order is
     *     whatever the database returns and pages may overlap or drop rows between requests
     * @return an empty page, never {@code null}, when the account has no transactions
     */
    Page<TransactionEntity> findByAccountId(Long accountId, Pageable pageable);

    /**
     * Returns one page of an account's transactions narrowed to a single direction.
     *
     * @param accountId never {@code null}; not ownership-checked here, the caller must have done that
     * @param transactionType never {@code null}; use {@link #findByAccountId} for the unfiltered case
     * @param pageable supplies limit, offset and sort; see {@link #findByAccountId} on sort stability
     * @return an empty page, never {@code null}, when nothing matches
     */
    Page<TransactionEntity> findByAccountIdAndTransactionType(Long accountId, TransactionType transactionType, Pageable pageable);

    /**
     * Reports whether a ledger write under this idempotency key has already been applied.
     *
     * <p>The fast path of the idempotency check, letting a replay be answered without touching a
     * balance. It is not the whole mechanism: this read and the insert that follows it are two steps,
     * and two concurrent retries can both see {@code false} before either writes. The unique index
     * added in migration V8 is what actually settles that race, so a caller must still be prepared
     * for the insert to fail on a constraint violation after this returns {@code false}.
     *
     * @param idempotencyKey never {@code null}; a {@code null} key means "not idempotent" and must be
     *     screened out by the caller rather than passed here
     * @return {@code true} if any transaction already carries the key
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Returns one page of transactions across several accounts, with three optional filters.
     *
     * <p>Powers the cross-account History view. Each of {@code type}, {@code from} and {@code to} may
     * be {@code null} to mean "unfiltered"; the {@code IS NULL OR} pattern lets a single query cover
     * every combination instead of an explosion of derived method names.
     *
     * <p>The {@code CAST(... AS string)} wrapping each null test is load-bearing, not decoration. A
     * bare {@code :from IS NULL} sends an untyped null to Postgres, which rejects it with
     * {@code could not determine data type of parameter} and fails the whole query — History returned
     * a 500 for every user whenever a filter was left blank, which is the page's default state. The
     * cast gives that bind a concrete type while the comparison beside it still binds the parameter
     * as its real type, so filtering behaviour is unchanged. Do not remove the casts.
     *
     * @param accountIds never {@code null} or empty; ownership of every id must already have been
     *     verified by the caller, this query enforces none
     * @param type {@code null} for both directions
     * @param from {@code null} for no lower bound, otherwise inclusive
     * @param to {@code null} for no upper bound, otherwise inclusive — a date-only value binds at
     *     midnight, so entries later that day fall outside the range
     * @param pageable supplies limit, offset and sort
     * @return an empty page, never {@code null}, when nothing matches
     */
    @Query("SELECT t FROM TransactionEntity t WHERE t.accountId IN :accountIds " +
           "AND (CAST(:type AS string) IS NULL OR t.transactionType = :type) " +
           "AND (CAST(:from AS string) IS NULL OR t.createdAt >= :from) " +
           "AND (CAST(:to AS string) IS NULL OR t.createdAt <= :to)")
    Page<TransactionEntity> findByAccountIdInWithFilters(@Param("accountIds") List<Long> accountIds,
                                                          @Param("type") TransactionType type,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          Pageable pageable);
}