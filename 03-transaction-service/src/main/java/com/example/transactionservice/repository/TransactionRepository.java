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

/**
 * Persistence access for {@code wire_transactions}.
 *
 * <p>Keyed by {@link UUID} rather than a generated number because this entity's primary key is the
 * client-facing confirmation id itself.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Returns one page of the caller's transfers, narrowed by any combination of status and date
     * bounds.
     *
     * <p>Backs the History view. Every filter is independently optional: the query uses an
     * "is null or matches" test per filter so a single statement covers all eight combinations
     * rather than branching into separate finders.
     *
     * <p>Each null test wraps the parameter in {@code CAST(... AS string)}. Without it Postgres
     * rejects the untyped null bind with "could not determine data type of parameter", which turned
     * any unset filter into a 500 on the History page. The comparison beside each cast still binds
     * the parameter as its real type, so filtering behaviour is unaffected.
     *
     * <p>This performs no ownership check of its own. {@code accountIds} is trusted as already
     * resolved and ownership-verified against account-service; passing an id the caller does not own
     * returns another customer's transfers.
     *
     * @param accountIds the caller's own verified account ids; an empty list matches nothing rather
     *     than everything
     * @param status optional review state; {@code null} leaves the status filter off
     * @param from optional inclusive lower bound on {@code createdAt}; {@code null} leaves it off
     * @param to optional inclusive upper bound on {@code createdAt}; {@code null} leaves it off
     * @param pageable carries the page size and sort; the query itself imposes no ordering, so an
     *     unsorted request returns rows in an unspecified order
     * @return the requested page, empty rather than {@code null} when nothing matches
     */
    @Query("SELECT t FROM TransactionEntity t WHERE t.accountId IN :accountIds " +
           "AND (CAST(:status AS string) IS NULL OR t.status = :status) " +
           "AND (CAST(:from AS string) IS NULL OR t.createdAt >= :from) " +
           "AND (CAST(:to AS string) IS NULL OR t.createdAt <= :to)")
    Page<TransactionEntity> findByAccountIdInWithFilters(@Param("accountIds") List<Long> accountIds,
                                                          @Param("status") TransactionStatus status,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          Pageable pageable);
}
