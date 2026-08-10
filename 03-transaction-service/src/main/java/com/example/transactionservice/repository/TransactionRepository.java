package com.example.transactionservice.repository;

import com.example.transactionservice.model.TransactionEntity;
import com.example.transactionservice.model.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// <TransactionEntity, UUID> since this entity's primary key is the client facing confirmation
// uuid itself, not an auto generated number like most of the other entities in this project use
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    // Powers GET /api/v1/transfers (History) - accountIds is the caller's own set, resolved and
    // ownership-verified via account-service's internal by-user endpoint before this is ever
    // called. status/from/to are each optional; the "IS NULL OR ..." pattern covers every filter
    // combination with one query, same approach account-service's own history query uses.
    @Query("SELECT t FROM TransactionEntity t WHERE t.accountId IN :accountIds " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:from IS NULL OR t.createdAt >= :from) " +
           "AND (:to IS NULL OR t.createdAt <= :to)")
    Page<TransactionEntity> findByAccountIdInWithFilters(@Param("accountIds") List<Long> accountIds,
                                                          @Param("status") TransactionStatus status,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          Pageable pageable);
}
