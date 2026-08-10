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

// V6__Add_Iban_To_Accounts.sql added the iban column as nullable and deliberately left existing rows
// alone, so every account opened before that migration has none. That isn't cosmetic: an account
// without an IBAN can't be handed out to receive money on the Transfer page's External Wire tab, and
// the Profile page's "Receive Money" panel had nothing to show for it.
//
// Done here in Java rather than as a follow-up migration so the ISO 7064 mod-97 checksum comes from
// the one implementation that already exists and is covered by tests (IbanGenerator), instead of
// being written a second time in SQL where the two could drift apart.
@Component
public class IbanBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(IbanBackfillRunner.class);

    // Same value AccountService and UserRegisteredListener assign to new accounts - a backfilled IBAN
    // has to be built from the same routing number the account itself carries, or it wouldn't match
    // the account it belongs to.
    private static final String DEFAULT_ROUTING_NUMBER = "021000021";

    private final AccountRepository accountRepository;
    private final IbanGenerator ibanGenerator;

    public IbanBackfillRunner(AccountRepository accountRepository, IbanGenerator ibanGenerator) {
        this.accountRepository = accountRepository;
        this.ibanGenerator = ibanGenerator;
    }

    // Idempotent by construction: it only ever selects rows where iban is null, so the second and
    // every later startup finds nothing and does nothing.
    //
    // Wrapped so a failure can never stop the service booting. This is opportunistic housekeeping on
    // historical rows, not part of serving any request - a context with no accounts table at all
    // (the slice used by AccountServiceApplicationTests) must still start cleanly. Logged at error
    // level so a genuine failure is still loud.
    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillMissingIbans();
        } catch (RuntimeException e) {
            logger.error("IBAN backfill did not run. Accounts predating the iban column will keep showing "
                    + "no IBAN until this succeeds; nothing else is affected.", e);
        }
    }

    @Transactional
    public void backfillMissingIbans() {
        List<AccountEntity> missing = accountRepository.findByIbanIsNull();

        if (missing.isEmpty()) {
            return;
        }

        int skipped = 0;
        for (AccountEntity account : missing) {
            // An account with no account number can't produce a meaningful IBAN, and inventing one
            // would create an identifier that doesn't correspond to the account. Leave it alone and
            // say so rather than writing something wrong.
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
