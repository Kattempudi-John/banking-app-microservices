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

/**
 * Emails a user an alert when one of their transfers meets or exceeds their configured alert
 * threshold.
 *
 * <p>Account references in the alert are masked to their last four digits and stay masked in the
 * stored record, which is served back by {@code GET /api/v1/notifications} indefinitely.
 * Counterparty name resolution is best effort: every lookup failure degrades to the masked account
 * alone and the alert still goes out, because a large-transaction warning must never be lost to an
 * unavailable downstream.
 */
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

    /**
     * Consumes a completed transfer and sends an alert if it meets the user's threshold.
     *
     * <p>An alert is raised when the amount is greater than or equal to the threshold, not strictly
     * greater, so a transfer landing exactly on the configured figure does alert. Below-threshold
     * transfers produce no email and no record at all.
     *
     * <p>Never throws. Any failure — a missing preferences record, an unavailable downstream, a
     * malformed payload — is logged and the message treated as consumed, because an exception
     * escaping a Kafka listener redelivers the same message forever and blocks the partition behind
     * it.
     *
     * @param event must carry a {@code userId} that owns the debited account; {@code fromAccountId}
     *     comes from a different id sequence and would resolve another user's preferences
     */
    @KafkaListener(topics = "successful-transfers", groupId = "notification-service-group")
    public void consumeTransferEvent(FundsTransferredEvent event) {
        log.debug("Received transfer event for Transaction ID: {}", event.transactionId());

        try {
            ProfileServiceClient.UserPreferenceResponse preferences =
                    profileServiceClient.getUserPreferences(event.userId());

            if (preferences == null) {
                log.warn("No preferences found for User ID: {}. Skipping alert.", event.userId());
                return;
            }

            evaluateAndDispatchAlert(event, preferences);

        } catch (Exception e) {
            log.error("Failed to process Kafka event for Transaction ID: {}", event.transactionId(), e);
        }
    }

    private void evaluateAndDispatchAlert(FundsTransferredEvent event, ProfileServiceClient.UserPreferenceResponse preferences) {
        if (event.amount().compareTo(preferences.alertThresholdAmount()) >= 0) {

            log.info("Transaction {} (Amount: ${}) exceeded threshold (${}). Dispatching alert.",
                    event.transactionId(), event.amount(), preferences.alertThresholdAmount());

            String subject = "Bank Alert: Large Debit Transaction";
            String htmlMessage = buildHtmlMessage(event, resolveCounterparty(event));

            String userEmail = preferences.email();
            if (userEmail == null || userEmail.isBlank()) {
                log.warn("User {} has no email address on file - skipping the alert for transaction {}",
                        event.userId(), event.transactionId());
                persistRecord(event.userId(), subject, htmlMessage, NotificationStatus.FAILED);
                return;
            }

            boolean dispatched = notificationProviderService.dispatchEmail(userEmail, subject, htmlMessage);

            persistRecord(event.userId(), subject, htmlMessage, dispatched ? NotificationStatus.SENT : NotificationStatus.FAILED);

        } else {
            log.debug("Transaction {} (Amount: ${}) is below threshold (${}). No alert needed.",
                    event.transactionId(), event.amount(), preferences.alertThresholdAmount());
        }
    }

    private record Counterparty(String maskedAccount, String displayName, boolean ownAccount) {}

    private Counterparty resolveCounterparty(FundsTransferredEvent event) {
        String maskedAccount = maskAccountReference(event.toAccountId());

        Long ownerUserId = lookupAccountOwner(event.toAccountId());
        if (ownerUserId == null) {
            return new Counterparty(maskedAccount, null, false);
        }
        if (ownerUserId.equals(event.userId())) {
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

    private String maskAccountReference(Long accountId) {
        if (accountId == null) {
            return "........";
        }
        String digits = String.valueOf(accountId);
        String lastFour = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        return "........" + lastFour;
    }

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
