package com.example.accountservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Maps a row of the {@code transactions} table — one immutable ledger movement against one account.
 *
 * <p>{@code accountId} is a plain {@code Long}, not a {@code @ManyToOne AccountEntity}, so that
 * reading a transaction never joins or lazy-loads the whole account graph and the two domains stay
 * decoupled. Amounts are {@link BigDecimal} at precision 19, scale 4 — money is never a
 * floating-point type here.
 *
 * <p>{@code idempotencyKey} is optional and backed by a unique index (migration V8). It is nullable
 * because a {@code null} key must behave exactly as before, leaving existing callers that pass none
 * unchanged; it is non-updatable because rewriting the key on an existing row would let a replay be
 * re-admitted under a key the ledger has already spent.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    public TransactionEntity() {}

    /**
     * Defaults {@code createdAt} to the current time only when the caller left it unset.
     *
     * <p>The conditional is deliberate: it lets the demo transaction seeder backdate entries through
     * {@code setCreatedAt}, while every real caller — which never sets it — is unaffected. Making the
     * stamp unconditional would silently collapse seeded history onto today.
     *
     * <p>Called by the persistence provider, never directly.
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}