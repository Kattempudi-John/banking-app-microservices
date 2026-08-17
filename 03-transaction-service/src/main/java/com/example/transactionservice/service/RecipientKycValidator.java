package com.example.transactionservice.service;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import com.example.transactionservice.client.ProfileServiceClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Checks the receiving half of the KYC gate, which the caller-side aspect structurally cannot.
 *
 * <p>{@code KycEnforcementAspect} resolves its subject from the JWT, and a recipient has no session
 * here, so every path that credits somebody else's account has to call this explicitly. It is a
 * plain component rather than a second aspect because the recipient's id only comes into existence
 * partway through the method body, after an account lookup, which {@code @Before} advice cannot
 * see.
 *
 * <p>Both methods fail closed and neither distinguishes "not approved" from "could not ask".
 */
@Component
public class RecipientKycValidator {

    private final ProfileServiceClient profileServiceClient;

    public RecipientKycValidator(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    /**
     * Aborts the caller unless the recipient's verification is approved.
     *
     * <p>Must be called before the money movement, not after: account-service debits and credits
     * atomically, so there is no half of a completed transfer to undo once it has returned.
     *
     * <p>Throws the same type the caller-side gate uses, so the existing 403 mapping covers this
     * with no second handler. The message is deliberately generic — the sender is entitled to know
     * that they cannot pay this person and nothing more, since naming the actual status would leak
     * a stranger's standing with the bank to anyone who guesses their account number.
     *
     * @param recipientUserId owner id resolved from an account lookup; a {@code null} or unknown id
     *     is a refusal, not a pass
     * @throws com.example.transactionservice.aspect.KycEnforcementAspect.KycRequiredException when
     *     the recipient is not approved, including when profile-service could not be reached at all
     */
    public void requireApprovedRecipient(Long recipientUserId) {
        if (!isApproved(recipientUserId)) {
            throw new KycEnforcementAspect.KycRequiredException(
                    "This recipient can't receive transfers yet - their identity verification isn't complete.");
        }
    }

    /**
     * Reports whether a recipient's verification is approved, without throwing.
     *
     * <p>The non-throwing form exists for the read-only recipient preview, which has to keep
     * answering when profile-service is down rather than fail the page; there an outage shows as
     * "unverified". Money-moving callers should prefer {@link #requireApprovedRecipient} so a
     * refusal cannot be forgotten.
     *
     * <p>Treats every answer that is not a positive {@code APPROVED} as false: a {@code null} body,
     * a body without the {@code status} key, and any exception from profile-service all collapse to
     * the same result. That deliberately differs from the caller-side aspect, which reports an
     * outage as a distinct 503.
     *
     * @param recipientUserId owner id resolved from an account lookup; never trusted from client
     *     input
     * @return {@code true} only for a literal {@code APPROVED} status
     */
    public boolean isApproved(Long recipientUserId) {
        try {
            Map<String, String> response = profileServiceClient.getKycStatus(recipientUserId);
            return response != null && "APPROVED".equals(response.get("status"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
