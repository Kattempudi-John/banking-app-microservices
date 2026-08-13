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

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    // learned passing a pageable straight into a derived query method is enough for spring data
    // to add the limit/offset and sorting itself, no manual sql pagination math required
    Page<TransactionEntity> findByAccountId(Long accountId, Pageable pageable);

    Page<TransactionEntity> findByAccountIdAndTransactionType(Long accountId, TransactionType transactionType, Pageable pageable);

    // The fast path of the idempotency check: a replay is answered without touching a balance at all.
    // Not the whole mechanism though - this read and the insert that follows it are two steps, and two
    // concurrent retries can both find nothing before either writes. The unique index from V8 is what
    // actually settles that race; see InternalAccountService.recordTransaction.
    boolean existsByIdempotencyKey(String idempotencyKey);

    // Powers the cross-account History view - accountIds is the caller's own (possibly
    // single-account-narrowed) set, resolved and ownership-checked in AccountService before this
    // is ever called. type/from/to are each optional; the "IS NULL OR ..." pattern lets one query
    // cover every filter combination instead of a derived-method-name explosion.
    //
    // The CAST(... AS string) around each null check is load-bearing, not decoration. A bare
    // ":from IS NULL" sends an untyped null to Postgres, which refuses it with
    // "ERROR: could not determine data type of parameter $5" and fails the whole query - so History
    // returned a 500 for every user whenever a filter was left blank, which is the default state of
    // the page. The cast gives that bind a concrete type; the comparison beside it still binds the
    // parameter as its real type, so filtering behaviour is unchanged.
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