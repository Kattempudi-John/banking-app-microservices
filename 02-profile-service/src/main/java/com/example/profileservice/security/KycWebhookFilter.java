package com.example.profileservice.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates the identity vendor's KYC callback by HMAC-SHA256 over the raw request body.
 *
 * <p>This filter is what makes {@code POST /api/v1/webhooks/kyc-update} safe to leave outside the
 * JWT rule: the vendor holds no end-user token, so a valid signature over the exact bytes sent is
 * the only proof of origin. Everything the webhook can do — promoting a user to {@code APPROVED} and
 * thereby letting money move — rests on this check, so an unsigned or mis-signed request is answered
 * {@code 401} and never reaches the controller.
 *
 * <p>Unlike {@link InternalTokenFilter}, which gates a whole prefix, this filter examines only the
 * one webhook path and passes every other request through untouched.
 *
 * <p>The request is wrapped so its body can be read twice. A servlet body is normally consumable
 * once, and the signature must be computed over the raw bytes before the controller parses the same
 * bytes as JSON; the wrapper caches them so both reads succeed. The wrapped request — not the
 * original — is what continues down the chain, so removing the wrapper would leave the controller
 * reading an exhausted stream.
 */
@Component
public class KycWebhookFilter extends OncePerRequestFilter {

    /**
     * Shared secret the vendor signs with; supplied by the vendor and injected securely in a real
     * deployment, defaulted only so local runs work with no config.
     */
    @Value("${kyc.vendor.webhook.secret:SuperSecretVendorKey123!}")
    private String webhookSecret;

    private static final String WEBHOOK_PATH = "/api/v1/webhooks/kyc-update";
    private static final String SIGNATURE_HEADER = "X-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Verifies the {@code X-Signature} header on the KYC webhook and rejects anything that fails.
     *
     * <p>Signature comparison is constant-time: {@code String.equals} stops at the first mismatched
     * byte, so its timing would leak how much of a forged signature was correct.
     *
     * @param request only URIs containing {@code /api/v1/webhooks/kyc-update} are inspected; all
     *     others continue unmodified
     * @param response written with {@code 401} and a JSON {@code error} when the signature header is
     *     absent, blank, or does not match, in which case the chain is not continued
     * @param filterChain continued with a body-caching wrapper around {@code request}, never the
     *     original, so the controller can still read the payload
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!request.getRequestURI().contains(WEBHOOK_PATH)) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        String signatureError = verifySignature(wrappedRequest);
        if (signatureError != null) {
            rejectRequest(response, signatureError);
            return;
        }

        filterChain.doFilter(wrappedRequest, response);
    }

    private String verifySignature(CachedBodyHttpServletRequest wrappedRequest) {
        String vendorSignature = wrappedRequest.getHeader(SIGNATURE_HEADER);
        if (vendorSignature == null) {
            return "Missing X-Signature header";
        }
        if (vendorSignature.isBlank()) {
            return "Missing X-Signature header";
        }

        String body = new String(wrappedRequest.getCachedBody(), StandardCharsets.UTF_8);
        String calculatedSignature = calculateHmac(body, webhookSecret);

        if (!isSignatureValid(vendorSignature, calculatedSignature)) {
            return "Invalid webhook signature";
        }
        return null;
    }

    private String calculateHmac(String data, String key) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC", e);
        }
    }

    private boolean isSignatureValid(String expected, String actual) {
        if (expected == null) return false;
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void rejectRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }

    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            InputStream requestInputStream = request.getInputStream();
            this.cachedBody = requestInputStream.readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(this.cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
            return new BufferedReader(new InputStreamReader(byteArrayInputStream));
        }

        public byte[] getCachedBody() {
            return this.cachedBody;
        }

        private static class CachedBodyServletInputStream extends ServletInputStream {
            private final InputStream cachedBodyInputStream;

            public CachedBodyServletInputStream(byte[] cachedBody) {
                this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
            }

            @Override
            public boolean isFinished() {
                try { return cachedBodyInputStream.available() == 0; }
                catch (IOException e) { return true; }
            }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() throws IOException {
                return cachedBodyInputStream.read();
            }
        }
    }
}
