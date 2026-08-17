package com.example.transactionservice.controller;

import com.example.transactionservice.client.AccountServiceClient;
import com.example.transactionservice.dto.ExternalWireRequestDto;
import com.example.transactionservice.dto.TransferResponseDto;
import com.example.transactionservice.model.TransactionEntity;
import com.example.transactionservice.model.TransactionStatus;
import com.example.transactionservice.repository.TransactionRepository;
import com.example.transactionservice.service.ExternalWireService;
import com.example.transactionservice.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer-facing transfer API.
 *
 * <p>Every endpoint requires a fully authenticated token: a session that has passed login but not
 * yet a second factor carries a partial scope and is rejected at the class level, before any
 * handler runs. Account ids in a request body are never trusted for authorisation — ownership is
 * resolved server-side from the {@code userId} claim on each call.
 *
 * <p>The money-moving endpoints additionally require the caller's identity verification to be
 * approved, which the service layer enforces; the read-only ones deliberately do not.
 */
@RestController
@RequestMapping("/api/v1/transfers")
@PreAuthorize("hasAuthority('SCOPE_FULL_AUTH')")
public class TransferController {

    private final TransferService transferService;
    private final ExternalWireService externalWireService;
    private final TransferHistoryService transferHistoryService;

    public TransferController(TransferService transferService, ExternalWireService externalWireService,
                               TransferHistoryService transferHistoryService) {
        this.transferService = transferService;
        this.externalWireService = externalWireService;
        this.transferHistoryService = transferHistoryService;
    }

    /**
     * Returns the caller's wire-transfer history, newest first.
     *
     * <p>Covers wires only. Deposits and internal-transfer ledger entries live in account-service
     * and are fetched separately, so this is one half of what a user sees as their history and will
     * look empty for a user who has only ever made internal transfers.
     *
     * @param accountId optional; when given it must be an account the caller owns or the request is
     *     refused, and when omitted the result spans every account they own
     * @param status optional exact-match filter on transaction status
     * @param from optional inclusive lower bound, ISO date-time
     * @param to optional inclusive upper bound, ISO date-time
     * @param pageable defaults to 50 rows sorted by {@code createdAt} descending if the client
     *     sends nothing
     * @return a page of transactions; empty rather than an error when the caller owns no accounts
     * @throws org.springframework.web.server.ResponseStatusException with {@code FORBIDDEN} when
     *     {@code accountId} names an account the caller does not own
     */
    @GetMapping
    public ResponseEntity<Page<TransactionEntity>> getTransferHistory(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long userId = extractUserIdFromAuth();

        Page<TransactionEntity> transfers = transferHistoryService.getTransferHistory(userId, accountId, status, from, to, pageable);

        return ResponseEntity.ok(transfers);
    }

    /**
     * Request to move funds between two accounts the caller already owns.
     *
     * @param fromAccountId required; ownership is verified server-side, not taken on trust
     * @param toAccountId required; must also belong to the caller, or the transfer is refused
     * @param amount required and strictly positive; zero and negative values are rejected as 400
     */
    public record InternalTransferRequestDto(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive BigDecimal amount
    ) {}

    /**
     * Moves funds between two of the caller's own accounts and confirms immediately.
     *
     * <p>Completes synchronously or not at all — there is no pending state on this path and no
     * fraud threshold, both accounts being on this platform and both belonging to the caller.
     *
     * @param request both accounts must be owned by the authenticated caller
     * @return 200 with a confirmation whose status is always {@code COMPLETED}
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycRequiredException as a
     *     403 when the caller's identity verification is not approved
     */
    @PostMapping("/internal")
    public ResponseEntity<TransferResponseDto> executeInternalTransfer(
            @RequestBody @Valid InternalTransferRequestDto request) {

        Long userId = extractUserIdFromAuth();

        TransferResponseDto response = transferService.executeTransfer(
                userId,
                request.fromAccountId(),
                request.toAccountId(),
                request.amount()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Request to pay another person's account by its full account number.
     *
     * @param fromAccountId required; must belong to the caller
     * @param recipientAccountNumber required and non-blank; the full number, as the sender typed it
     * @param amount required and strictly positive
     */
    public record RecipientTransferRequestDto(
            @NotNull Long fromAccountId,
            @NotBlank String recipientAccountNumber,
            @NotNull @Positive BigDecimal amount
    ) {}

    /**
     * What a sender is shown about a recipient before committing to pay them.
     *
     * @param maskedAccountNumber partially redacted; the full number is never echoed back
     * @param accountType product label, for display only
     * @param displayName may be {@code null} when no name could be resolved, which does not stop
     *     the payment
     * @param verified whether a transfer to this recipient would currently be accepted, assembled
     *     from this service's own KYC check so account-service need know nothing about KYC;
     *     deliberately a bare flag rather than the real status, because the sender needs only
     *     "can they be paid" and has no business seeing someone else's verification standing
     */
    public record RecipientPreviewDto(String maskedAccountNumber, String accountType, String displayName,
                                      boolean verified) {}

    /**
     * Resolves a recipient account number so the sender can confirm who they are paying.
     *
     * <p>Read-only and intentionally ungated by KYC: looking up a name moves no money, and refusing
     * it with a verification error would misdescribe the problem. For the same reason an unverified
     * recipient answers 200 with {@code verified} false instead of the 403 the send itself would
     * give — this is where the frontend warns, not where it blocks.
     *
     * <p>Discloses nothing the sender was not already told by the recipient: a name and a masked
     * number, both of which they need to have had the account number at all.
     *
     * @param accountNumber the full account number as typed, never blank
     * @return 200 with the preview
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} and a
     *     message about the number typed when no account holds it
     */
    @GetMapping("/recipients/{accountNumber}")
    public ResponseEntity<RecipientPreviewDto> previewRecipient(@PathVariable String accountNumber) {
        var recipient = transferService.resolveRecipient(accountNumber);
        String displayName = transferService.resolveRecipientName(recipient.ownerUserId());
        boolean verified = transferService.isRecipientVerified(recipient.ownerUserId());

        return ResponseEntity.ok(new RecipientPreviewDto(
                recipient.maskedAccountNumber(), recipient.accountType(), displayName, verified));
    }

    /**
     * Pays another person's account by number and confirms immediately.
     *
     * <p>The only path where both parties are verified: the sender by the gate on the service
     * method, the recipient by an explicit check made before any money moves. A 403 here may
     * therefore be about either party, so clients must show the message that arrives rather than
     * assume it concerns the sender.
     *
     * @param request the recipient account number must resolve to an existing account owned by an
     *     approved user
     * @return 200 with a confirmation whose status is always {@code COMPLETED}
     * @throws org.springframework.web.server.ResponseStatusException with {@code NOT_FOUND} when no
     *     account holds that number
     */
    @PostMapping("/to-recipient")
    public ResponseEntity<TransferResponseDto> executeTransferToRecipient(
            @RequestBody @Valid RecipientTransferRequestDto request) {

        Long userId = extractUserIdFromAuth();

        TransferResponseDto response = transferService.executeTransferToRecipient(
                userId,
                request.fromAccountId(),
                request.recipientAccountNumber(),
                request.amount()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Sends an outgoing wire, which may complete at once or be held for fraud review.
     *
     * <p>Unlike the other transfer endpoints this one can answer 200 with a status of
     * {@code PENDING_APPROVAL}: any amount strictly above {@code 5000.00} is held for review, while
     * exactly {@code 5000.00} clears. A held wire has already debited the sender, so a client must
     * present that response as "sent, awaiting review" and not as "not yet sent".
     *
     * <p>A failure part-way through can leave the sender debited with no wire recorded and no
     * automatic refund, and the request carries no idempotency key, so a client must not blindly
     * retry a wire whose outcome it does not know.
     *
     * @param fromAccountId read from the query string while the rest of the wire comes from the
     *     body; must be an account the caller owns
     * @param request IBAN must pass mod-97 and the BIC must be 8 or 11 characters; when the IBAN
     *     turns out to belong to an account on this platform the BIC must also match that account's
     *     bank, which is the only case where a wrong BIC can be detected at all
     * @return 200 with the wire id, a status of either {@code COMPLETED} or
     *     {@code PENDING_APPROVAL}, and whether the wire stayed on this platform
     * @throws org.springframework.web.server.ResponseStatusException with {@code BAD_REQUEST} for a
     *     malformed or mismatched IBAN/BIC pair
     */
    @PostMapping("/external")
    public ResponseEntity<TransferResponseDto> executeExternalWire(
            @RequestParam Long fromAccountId,
            @RequestBody @Valid ExternalWireRequestDto request) {

        Long userId = extractUserIdFromAuth();

        TransferResponseDto response = externalWireService.initiateWire(
                userId,
                fromAccountId,
                request
        );

        return ResponseEntity.ok(response);
    }

    private Long extractUserIdFromAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new SecurityException("User is not authenticated");
        }
        if (!authentication.isAuthenticated()) {
            throw new SecurityException("User is not authenticated");
        }
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}

@Service
class TransferHistoryService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    TransferHistoryService(TransactionRepository transactionRepository, AccountServiceClient accountServiceClient) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
    }

    Page<TransactionEntity> getTransferHistory(Long userId, Long accountIdFilter, TransactionStatus status,
                                                LocalDateTime from, LocalDateTime to, Pageable pageable) {
        List<Long> ownedAccountIds = accountServiceClient.getAccountIdsByUser(userId);

        List<Long> accountIds;
        if (accountIdFilter != null) {
            if (!ownedAccountIds.contains(accountIdFilter)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Action forbidden: You do not have permission to view this account's history.");
            }
            accountIds = List.of(accountIdFilter);
        } else {
            accountIds = ownedAccountIds;
        }

        if (accountIds.isEmpty()) {
            return Page.empty(pageable);
        }

        return transactionRepository.findByAccountIdInWithFilters(accountIds, status, from, to, pageable);
    }
}
