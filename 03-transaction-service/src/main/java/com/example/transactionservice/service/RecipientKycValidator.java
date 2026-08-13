package com.example.transactionservice.service;

import com.example.transactionservice.aspect.KycEnforcementAspect;
import com.example.transactionservice.client.ProfileServiceClient;
import org.springframework.stereotype.Component;

import java.util.Map;

// The receiving half of the KYC gate. KycEnforcementAspect can only ever check the caller - it
// resolves the user from the JWT, and the recipient has no session here - so a transfer's
// destination needs this explicit call from the services that know who the recipient is.
// Deliberately a plain @Component rather than a second aspect: the recipient's id only exists
// after an account lookup inside the method body, which an @Before advice cannot see.
@Component
public class RecipientKycValidator {

    private final ProfileServiceClient profileServiceClient;

    public RecipientKycValidator(ProfileServiceClient profileServiceClient) {
        this.profileServiceClient = profileServiceClient;
    }

    // Reuses the aspect's own exception type so GlobalExceptionHandler's existing 403 mapping
    // covers this too, no new handler needed.
    public void requireApprovedRecipient(Long recipientUserId) {
        if (!isApproved(recipientUserId)) {
            // The message stays generic on purpose. The sender is not entitled to know anything
            // about someone else's verification file beyond "you can't pay them yet" - naming the
            // actual status (REJECTED vs PENDING_VERIFICATION) would leak the recipient's standing
            // with the bank to whoever guesses their account number.
            throw new KycEnforcementAspect.KycRequiredException(
                    "This recipient can't receive transfers yet - their identity verification isn't complete.");
        }
    }

    // Fails closed on every non-APPROVED answer, including no answer at all: a null body, a body
    // without the status key, or profile-service being unreachable all count as not approved.
    // Rejected the alternative of rethrowing the way KycEnforcementAspect does (it turns an
    // unreachable profile-service into a 500) because this same check backs the read-only recipient
    // preview, which has to keep answering rather than blow up - there it just reports unverified.
    public boolean isApproved(Long recipientUserId) {
        try {
            Map<String, String> response = profileServiceClient.getKycStatus(recipientUserId);
            return response != null && "APPROVED".equals(response.get("status"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
