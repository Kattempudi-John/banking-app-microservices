package com.example.transactionservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Maps the {@code wire_transactions} table, this service's record of transfers it originated.
 *
 * <p>Account-service declares a class of the same name mapping {@code transactions}, its ledger of
 * per-account movements. The two are unrelated despite the shared simple name: this one owns
 * {@code wire_transactions} only, and an import of the wrong {@code TransactionEntity} compiles
 * cleanly while reading a different table.
 *
 * <p>The primary key is the client-facing confirmation UUID rather than a generated sequence, so it
 * is assigned by the caller before persisting and is safe to hand back in a response.
 *
 * <p>{@code destinationAccountId} is set only for an "on-us" wire whose IBAN resolved to an account
 * on this platform, and is {@code null} for a genuinely external wire, where funds leave the
 * platform and there is nothing to credit. {@code createdAt} is the sort and filter key behind the
 * History view and is never updated after insert.
 */
@Entity
@Table(name = "wire_transactions")
public class TransactionEntity {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false)
    private String description;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "swift_code", length = 11)
    private String swiftCode;

    @Column(name = "beneficiary_name", length = 100)
    private String beneficiaryName;

    @Column(name = "destination_account_id")
    private Long destinationAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TransactionEntity() {}

    /**
     * Stamps {@code createdAt} at insert time when the caller has not already set it.
     *
     * <p>An explicitly assigned value is left alone, so a backfill or a fixture can pin its own
     * timestamp. The column is not updatable, so this is the only chance to set it.
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIban() { return iban; }
    public void setIban(String iban) { this.iban = iban; }
    public String getSwiftCode() { return swiftCode; }
    public void setSwiftCode(String swiftCode) { this.swiftCode = swiftCode; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public void setBeneficiaryName(String beneficiaryName) { this.beneficiaryName = beneficiaryName; }
    public Long getDestinationAccountId() { return destinationAccountId; }
    public void setDestinationAccountId(Long destinationAccountId) { this.destinationAccountId = destinationAccountId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
