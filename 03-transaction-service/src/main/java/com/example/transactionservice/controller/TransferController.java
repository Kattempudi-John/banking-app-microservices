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

    // Powers the frontend's History page (the wire-transfer half of it - account-service's own
    // /api/v1/accounts/transactions covers deposits/internal-transfer ledger entries).
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

    // learned a record can be declared right inside a controller class like this, keeps a tiny
    // dto that only this one controller cares about from needing its own separate file
    public record InternalTransferRequestDto(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @Positive BigDecimal amount
    ) {}

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

    public record RecipientTransferRequestDto(
            @NotNull Long fromAccountId,
            @NotBlank String recipientAccountNumber,
            @NotNull @Positive BigDecimal amount
    ) {}

    public record RecipientPreviewDto(String maskedAccountNumber, String accountType, String displayName) {}

    // Read-only lookup the Transfer page calls as soon as a recipient account number is entered, so the
    // sender can confirm who they're paying before any money moves. Intentionally does NOT carry
    // @RequiresKyc - looking up a name moves no funds, and failing this with a KYC error would be
    // confusing. It leaks nothing beyond a name and a masked number, both of which the sender needs
    // to have been told by the recipient already.
    @GetMapping("/recipients/{accountNumber}")
    public ResponseEntity<RecipientPreviewDto> previewRecipient(@PathVariable String accountNumber) {
        var recipient = transferService.resolveRecipient(accountNumber);
        String displayName = transferService.resolveRecipientName(recipient.ownerUserId());

        return ResponseEntity.ok(new RecipientPreviewDto(
                recipient.maskedAccountNumber(), recipient.accountType(), displayName));
    }

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

    // mixing @RequestParam and @RequestBody on the same endpoint, learned spring is fine reading
    // fromAccountId off the query string while the rest of the payload comes from the json body
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
        // The JWT subject holds the username, not the id — auth-service puts the numeric
        // userId in its own claim instead, since this service has no User table to resolve it from.
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getClaim("userId");
    }
}

// Colocated with its controller the same way InternalFraudController's FraudResolutionService is -
// a small, distinct concern (querying transfer history) that doesn't belong on TransferService
// (transfer execution) or ExternalWireService (wire initiation).
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