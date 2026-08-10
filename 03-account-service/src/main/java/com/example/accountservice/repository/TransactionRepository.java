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

    // Powers the cross-account History view - accountIds is the caller's own (possibly
    // single-account-narrowed) set, resolved and ownership-checked in AccountService before this
    // is ever called. type/from/to are each optional; the "IS NULL OR ..." pattern lets one query
    // cover every filter combination instead of a derived-method-name explosion.
    @Query("SELECT t FROM TransactionEntity t WHERE t.accountId IN :accountIds " +
           "AND (:type IS NULL OR t.transactionType = :type) " +
           "AND (:from IS NULL OR t.createdAt >= :from) " +
           "AND (:to IS NULL OR t.createdAt <= :to)")
    Page<TransactionEntity> findByAccountIdInWithFilters(@Param("accountIds") List<Long> accountIds,
                                                          @Param("type") TransactionType type,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          Pageable pageable);
}