package com.example.accountservice.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.accountservice.model.AccountEntity;
import com.example.accountservice.repository.AccountRepository;
import com.example.accountservice.util.IbanGenerator;

/**
 * Fills in IBANs on startup for accounts that predate the {@code iban} column.
 *
 * <p>{@code V6__Add_Iban_To_Accounts.sql} added the column as nullable and left existing rows
 * alone, so every account opened before that migration has none. That is not cosmetic: an account
 * without an IBAN cannot be handed out to receive money on the Transfer page's External Wire tab,
 * and the Profile page's Receive Money panel has nothing to show for it.
 *
 * <p>Written in Java rather than as a follow-up migration so the ISO 7064 mod-97 checksum comes
 * from the one tested implementation, {@code IbanGenerator}, instead of being restated in SQL where
 * the two could drift apart.
 */
@Component
public class IbanBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(IbanBackfillRunner.class);

    private static final String DEFAULT_ROUTING_NUMBER = "021000021";

    private final AccountRepository accountRepository;
    private final IbanGenerator ibanGenerator;

    public IbanBackfillRunner(AccountRepository accountRepository, IbanGenerator ibanGenerator) {
        this.accountRepository = accountRepository;
        this.ibanGenerator = ibanGenerator;
    }

    /**
     * Runs the backfill once at startup, absorbing any failure so the service still boots.
     *
     * <p>This is opportunistic housekeeping on historical rows, not part of serving any request, so
     * it must never be able to keep the context from starting — a slice with no {@code accounts}
     * table at all still has to come up cleanly. A genuine failure is logged at error level and the
     * affected accounts simply keep showing no IBAN until a later startup succeeds.
     *
     * @param args the standard Boot arguments, unused
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillMissingIbans();
        } catch (RuntimeException e) {
            logger.error("IBAN backfill did not run. Accounts predating the iban column will keep showing "
                    + "no IBAN until this succeeds; nothing else is affected.", e);
        }
    }

    /**
     * Assigns an IBAN to every account that currently has none.
     *
     * <p>Idempotent by construction: it selects only rows where {@code iban} is null, so the second
     * and every later run finds nothing and writes nothing. Existing IBANs are never recomputed.
     *
     * <p>The whole sweep commits as one transaction, so either every eligible account gains an IBAN
     * or none does. An account with no account number is skipped and counted rather than given an
     * invented identifier that would not correspond to the account. The routing number stored on
     * the account is used where present, falling back to the platform default, because an IBAN
     * built from a different routing number would not match the account it belongs to.
     */
    @Transactional
    public void backfillMissingIbans() {
        List<AccountEntity> missing = accountRepository.findByIbanIsNull();

        if (missing.isEmpty()) {
            return;
        }

        int skipped = 0;
        for (AccountEntity account : missing) {
            if (account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
                skipped++;
                continue;
            }

            String routingNumber = account.getRoutingNumber() != null
                    ? account.getRoutingNumber()
                    : DEFAULT_ROUTING_NUMBER;

            account.setIban(ibanGenerator.generate(routingNumber, account.getAccountNumber()));
        }

        accountRepository.saveAll(missing);

        logger.info("Backfilled IBANs for {} account(s) that predate the iban column ({} skipped for a missing account number)",
                missing.size() - skipped, skipped);
    }
}
