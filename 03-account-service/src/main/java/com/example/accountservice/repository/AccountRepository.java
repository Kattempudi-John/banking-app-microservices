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

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    // "Not" in the method name flips the comparison to not equal, spring data derives the
    // whole where clause from this name alone, no @Query needed for something this simple
    List<AccountEntity> findByUserIdAndStatusNot(Long userId, AccountStatus status);

    boolean existsByUserId(Long userId);

    Optional<AccountEntity> findByIban(String iban);

    // Backs the recipient lookup on the Transfer page's "send to someone else" mode - the sender
    // types a full account number, which is the only account identifier a recipient would realistically
    // read out loud (the API only ever returns it masked).
    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    // Used once at startup by IbanBackfillRunner. Accounts created before the iban column existed
    // have none, and an account with no IBAN can't be sent money on the External Wire tab.
    List<AccountEntity> findByIbanIsNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a.userId, SUM(a.availableBalance) FROM AccountEntity a " +
           "WHERE a.userId IN :userIds AND a.status <> com.example.accountservice.model.AccountStatus.CLOSED " +
           "GROUP BY a.userId")
    List<Object[]> sumAvailableBalanceByUserIds(@Param("userIds") List<Long> userIds);
}