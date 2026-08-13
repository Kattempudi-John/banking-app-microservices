package com.example.notificationservice.service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.notificationservice.client.AccountServiceClient;
import com.example.notificationservice.client.AuthServiceClient;
import com.example.notificationservice.client.ProfileServiceClient;
import com.example.notificationservice.event.FundsTransferredEvent;
import com.example.notificationservice.model.NotificationChannel;
import com.example.notificationservice.model.NotificationRecord;
import com.example.notificationservice.model.NotificationStatus;
import com.example.notificationservice.model.NotificationType;
import com.example.notificationservice.repository.NotificationRecordRepository;

@Service
public class TransactionAlertListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionAlertListener.class);

    private final ProfileServiceClient profileServiceClient;
    private final AccountServiceClient accountServiceClient;
    private final AuthServiceClient authServiceClient;
    private final NotificationProviderService notificationProviderService;
    private final NotificationRecordRepository notificationRecordRepository;

    public TransactionAlertListener(ProfileServiceClient profileServiceClient,
                                    AccountServiceClient accountServiceClient,
                                    AuthServiceClient authServiceClient,
                                    NotificationProviderService notificationProviderService,
                                    NotificationRecordRepository notificationRecordRepository) {
        this.profileServiceClient = profileServiceClient;
        this.accountServiceClient = accountServiceClient;
        this.authServiceClient = authServiceClient;
        this.notificationProviderService = notificationProviderService;
        this.notificationRecordRepository = notificationRecordRepository;
    }

    @KafkaListener(topics = "successful-transfers", groupId = "notification-service-group")
    public void consumeTransferEvent(FundsTransferredEvent event) {
        log.debug("Received transfer event for Transaction ID: {}", event.transactionId());

        try {
            // 1. Perform sub-millisecond lookup via Cached Feign Client
            ProfileServiceClient.UserPreferenceResponse preferences =
                    profileServiceClient.getUserPreferences(event.userId());

            if (preferences == null) {
                log.warn("No preferences found for User ID: {}. Skipping alert.", event.userId());
                return;
            }

            // 2-4. Evaluate the transaction against the alert threshold and dispatch if it qualifies
            evaluateAndDispatchAlert(event, preferences);

        } catch (Exception e) {
            // We catch generic exceptions here so the Kafka Consumer doesn't crash
            // and get stuck in an infinite loop for a single bad message.
            log.error("Failed to process Kafka event for Transaction ID: {}", event.transactionId(), e);
        }
    }

    private void evaluateAndDispatchAlert(FundsTransferredEvent event, ProfileServiceClient.UserPreferenceResponse preferences) {
        if (event.amount().compareTo(preferences.alertThresholdAmount()) >= 0) {

            log.info("Transaction {} (Amount: ${}) exceeded threshold (${}). Dispatching alert.",
                    event.transactionId(), event.amount(), preferences.alertThresholdAmount());

            // Format the alert message. The counterparty lookups sit inside this branch on purpose -
            // most transfers never cross the threshold, and resolving a name for an alert that is not
            // going to be sent would be two HTTP calls per transfer for nothing.
            String subject = "Bank Alert: Large Debit Transaction";
            String htmlMessage = buildHtmlMessage(event, resolveCounterparty(event));

            // The real address, carried on the same preferences response the threshold above came
            // from. A user without one can't be alerted by email at all, so record the miss and stop
            // rather than dispatching to an address that would silently bounce.
            String userEmail = preferences.email();
            if (userEmail == null || userEmail.isBlank()) {
                log.warn("User {} has no email address on file - skipping the alert for transaction {}",
                        event.userId(), event.transactionId());
                persistRecord(event.userId(), subject, htmlMessage, NotificationStatus.FAILED);
                return;
            }

            // Delegate to the provider service (which handles its own external retries)
            boolean dispatched = notificationProviderService.dispatchEmail(userEmail, subject, htmlMessage);

            persistRecord(event.userId(), subject, htmlMessage, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);

        } else {
            log.debug("Transaction {} (Amount: ${}) is below threshold (${}). No alert needed.",
                    event.transactionId(), event.amount(), preferences.alertThresholdAmount());
        }
    }

    // Who the money went to, as far as we could find out. ownAccount means the destination belongs to
    // the same user being alerted - a transfer between their own checking and savings, which must not
    // be described as money leaving for someone else.
    private record Counterparty(String maskedAccount, String displayName, boolean ownAccount) {}

    // Best-effort, exactly like transaction-service's TransferService.resolveRecipientName: the alert
    // itself is the thing that matters, and a name is a nicety layered on top of it. Every failure
    // here degrades to the masked account number alone and the alert still goes out - a real
    // large-transaction warning must never be lost because a downstream lookup was unavailable.
    private Counterparty resolveCounterparty(FundsTransferredEvent event) {
        String maskedAccount = maskAccountReference(event.toAccountId());

        Long ownerUserId = lookupAccountOwner(event.toAccountId());
        if (ownerUserId == null) {
            return new Counterparty(maskedAccount, null, false);
        }
        if (ownerUserId.equals(event.userId())) {
            // Their own account. Naming them as the recipient of their own money would read as if a
            // stranger who happens to share their name had been paid.
            return new Counterparty(maskedAccount, null, true);
        }

        return new Counterparty(maskedAccount, lookupDisplayName(ownerUserId), false);
    }

    private Long lookupAccountOwner(Long accountId) {
        try {
            AccountServiceClient.AccountOwnerResponse owner = accountServiceClient.getAccountOwner(accountId);
            return owner != null ? owner.ownerUserId() : null;
        } catch (RuntimeException e) {
            log.warn("Could not resolve the owner of account {} for a transaction alert - the alert will "
                    + "name the masked account instead", accountId, e);
            return null;
        }
    }

    private String lookupDisplayName(Long ownerUserId) {
        try {
            AuthServiceClient.DisplayNameResponse response = authServiceClient.getDisplayName(ownerUserId);
            return response != null ? response.displayName() : null;
        } catch (RuntimeException e) {
            log.warn("Could not resolve a display name for user {} - the alert will name the masked "
                    + "account instead", ownerUserId, e);
            return null;
        }
    }

    // The one-line version of the alert: what moved, out of which account, into whose. The alert used
    // to say only the amount and a transaction id, which left the reader unable to tell a payment they
    // made from one they did not - the exact question a fraud alert exists to answer.
    private String buildSummaryLine(FundsTransferredEvent event, Counterparty counterparty, Instant sentAt) {
        String amount = formatAmount(event.amount());
        String from = maskAccountReference(event.fromAccountId());
        LocalDate date = LocalDate.ofInstant(sentAt, ZoneOffset.UTC);

        if (counterparty.ownAccount()) {
            return "%s moved between your own accounts, from %s to %s on %s. Reference %s."
                    .formatted(amount, from, counterparty.maskedAccount(), date, event.transactionId());
        }
        String destination = counterparty.displayName() == null || counterparty.displayName().isBlank()
                ? "account " + counterparty.maskedAccount()
                : "%s (%s)".formatted(counterparty.displayName(), counterparty.maskedAccount());

        return "%s sent from your account %s to %s on %s. Reference %s."
                .formatted(amount, from, destination, date, event.transactionId());
    }

    // learned java text blocks (triple quoted strings) let multi line html sit here without
    // escaping every single quote or concatenating a bunch of separate strings together
    private String buildHtmlMessage(FundsTransferredEvent event, Counterparty counterparty) {
        Instant sentAt = Instant.now();
        String recipientLine = counterparty.ownAccount()
                ? "Your own account " + counterparty.maskedAccount()
                : describeRecipient(counterparty);

        return """
               <html>
                   <body>
                       <h2>Transaction Alert</h2>
                       <p>%s</p>
                       <ul>
                           <li><strong>Amount:</strong> %s</li>
                           <li><strong>From:</strong> %s</li>
                           <li><strong>To:</strong> %s</li>
                           <li><strong>Transaction ID:</strong> %s</li>
                           <li><strong>Date:</strong> %s</li>
                       </ul>
                       <p>If you did not authorize this, please contact support immediately.</p>
                   </body>
               </html>
               """.formatted(buildSummaryLine(event, counterparty, sentAt),
                             formatAmount(event.amount()),
                             maskAccountReference(event.fromAccountId()),
                             recipientLine,
                             event.transactionId(),
                             sentAt.toString());
    }

    private String describeRecipient(Counterparty counterparty) {
        if (counterparty.displayName() == null || counterparty.displayName().isBlank()) {
            return counterparty.maskedAccount();
        }
        return "%s (%s)".formatted(counterparty.displayName(), counterparty.maskedAccount());
    }

    // "........5570", the same last-four-behind-dots shape account-service's AccountMapper produces
    // and the frontend already renders everywhere else, so one account reads the same in an alert as
    // it does on the dashboard.
    //
    // Masking an account IDENTIFIER rather than an account number, because an identifier is all a
    // transfer event carries - and it stays masked deliberately: this string is persisted and served
    // back by GET /api/v1/notifications, so an unmasked account reference would sit in the feed
    // indefinitely. The dot prefix is a fixed width rather than "length minus four" (AccountMapper's
    // rule): the id's length is not the account number's length, so reproducing it would only invent
    // a digit count that means nothing.
    private String maskAccountReference(Long accountId) {
        if (accountId == null) {
            return "........";
        }
        String digits = String.valueOf(accountId);
        String lastFour = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        return "........" + lastFour;
    }

    // "$1,000.00" rather than the raw BigDecimal's "1000.00" - the amount is the first thing read on
    // an alert, and a misread magnitude is the whole failure mode this notification guards against.
    private String formatAmount(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    private void persistRecord(Long userId, String subject, String message, NotificationStatus status) {
        NotificationRecord record = new NotificationRecord();
        record.setUserId(userId);
        record.setType(NotificationType.TRANSACTION_ALERT);
        record.setChannel(NotificationChannel.EMAIL);
        record.setSubject(subject);
        record.setMessage(message);
        record.setStatus(status);
        notificationRecordRepository.save(record);
    }
}
