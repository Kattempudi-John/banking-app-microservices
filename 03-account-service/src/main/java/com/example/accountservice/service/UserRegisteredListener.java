package com.example.accountservice.service;

import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.model.AccountType;
import com.example.accountservice.repository.AccountRepository;
import com.example.accountservice.util.IbanGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;

/**
 * Provisions a starter checking account when auth-service announces a newly registered user.
 *
 * <p>auth-service owns credentials only, so this event is how account-service learns a user exists
 * at all. Every account created here carries the platform's shared routing number; the account
 * number only has to be unique, nothing further is implied by the schema.
 */
@Service
public class UserRegisteredListener {

    private static final Logger logger = LoggerFactory.getLogger(UserRegisteredListener.class);
    private static final String DEFAULT_ROUTING_NUMBER = "021000021";

    private final AccountRepository accountRepository;
    private final IbanGenerator ibanGenerator;
    private final SecureRandom random = new SecureRandom();

    public UserRegisteredListener(AccountRepository accountRepository, IbanGenerator ibanGenerator) {
        this.accountRepository = accountRepository;
        this.ibanGenerator = ibanGenerator;
    }

    /**
     * Creates the user's first {@code ACTIVE} checking account, with a zero balance and a generated
     * IBAN.
     *
     * <p>Not gated by {@code @RequiresKyc}, and deliberately so: it writes the account directly
     * rather than through {@code AccountService.openAccount}, so it never crosses the proxy the KYC
     * aspect advises. A user must be provisioned while still at {@code PENDING_VERIFICATION} —
     * verification happens after registration, not before it. Note that this also means there is no
     * authenticated caller here for a gate to read.
     *
     * <p>Idempotent on the user: if the user already has any account, the event is a redelivery or
     * a retry and is skipped rather than handing out a second starter account.
     *
     * <p>Swallows every failure after logging it, so a malformed or unparseable message cannot stall
     * the consumer group by being redelivered forever. The consequence is that a failed event is
     * dropped — the user ends up with no account and no automatic repair; a production deployment
     * would route these to a dead-letter queue instead. The write itself is transactional, so a
     * partially built account is never committed.
     *
     * @param event the deserialized {@code user-events} payload; must carry a {@code userId} whose
     *     {@code toString} parses as a {@code long}, otherwise the event is logged and discarded
     */
    @KafkaListener(topics = "user-events", groupId = "account-service-group")
    @Transactional
    public void consumeUserRegistered(Map<String, Object> event) {
        try {
            Long userId = Long.valueOf(event.get("userId").toString());

            if (accountRepository.existsByUserId(userId)) {
                logger.info("Account already exists for user id {}, skipping provisioning", userId);
                return;
            }

            AccountEntity account = new AccountEntity();
            account.setUserId(userId);
            account.setAccountType(AccountType.CHECKING);
            account.setAvailableBalance(BigDecimal.ZERO);
            account.setRoutingNumber(DEFAULT_ROUTING_NUMBER);
            String accountNumber = generateAccountNumber();
            account.setAccountNumber(accountNumber);
            account.setIban(ibanGenerator.generate(DEFAULT_ROUTING_NUMBER, accountNumber));
            account.setStatus(AccountStatus.ACTIVE);
            accountRepository.save(account);

            logger.info("Provisioned starter checking account for newly registered user id {}", userId);
        } catch (Exception e) {
            logger.error("Failed to process UserRegistered event", e);
        }
    }

    private String generateAccountNumber() {
        long number = 100_000_000_000L + (long) (random.nextDouble() * 900_000_000_000L);
        return String.valueOf(number);
    }
}
