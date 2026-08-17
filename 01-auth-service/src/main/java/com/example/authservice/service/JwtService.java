package com.example.authservice.service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.example.authservice.model.User;
import com.example.authservice.security.TokenType;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Mints and reads the HS256 tokens every service in the platform trusts.
 *
 * <p>The signing key is shared with the downstream services, which validate these tokens as
 * OAuth2 resource servers; changing it here invalidates every token in flight everywhere. It
 * carries an inline default so a fresh checkout and the tests run without configuration, and
 * real environments override {@code application.security.jwt.secret-key} from a secret.
 */
@Service
public class JwtService {

    @Value("${application.security.jwt.secret-key:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}")
    private String secretKey;

    private static final long FULL_AUTH_EXPIRATION = 15 * 60 * 1000;
    private static final long PRE_AUTH_EXPIRATION = 5 * 60 * 1000;

    /**
     * Signs a token whose lifetime and privileges are decided entirely by the requested type.
     *
     * <p>A {@code FULL_AUTH} token lives 15 minutes and carries a {@code scope} claim of
     * {@code FULL_AUTH}. That claim is what the downstream resource servers turn into the
     * {@code SCOPE_FULL_AUTH} authority their {@code @PreAuthorize} rules test, so it is the
     * difference between a token that can move money and one that cannot. A {@code PRE_AUTH}
     * token lives 5 minutes and is given no scope at all, deliberately: it is only ever meant to
     * reach this service's own {@code /verify-2fa} endpoints, and the boundary is enforced twice,
     * by the absence of the scope downstream and by request-path checks in
     * {@code JwtAuthenticationFilter} here.
     *
     * <p>The numeric user id travels as its own {@code userId} claim rather than in the subject,
     * because downstream controllers key everything off it and none of them can reach this
     * service's user table to translate a username.
     *
     * <p>Each token gets a fresh {@code jti}, which is the handle logout blacklists it by; two
     * tokens minted for the same user are independently revocable because of it.
     *
     * @param userDetails must be this service's own {@code User} implementation, since the id
     *     claim is read off it by cast
     * @param tokenType {@code PRE_AUTH} until 2FA is cleared, {@code FULL_AUTH} after
     * @return the signed compact token, never {@code null}
     */
    public String generateToken(UserDetails userDetails, TokenType tokenType) {
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("token_type", tokenType.name());

        if (tokenType == TokenType.FULL_AUTH) {
            extraClaims.put("scope", "FULL_AUTH");
        }

        extraClaims.put("userId", ((User) userDetails).getId());

        long expirationMillis = (tokenType == TokenType.FULL_AUTH) ? FULL_AUTH_EXPIRATION : PRE_AUTH_EXPIRATION;

        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationMillis))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extracts the username the token was issued to.
     *
     * @param token must be a signed token from this service
     * @return the {@code sub} claim
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     already expired, so a caller must treat a thrown exception as "reject the request"
     *     rather than as a bug
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the token's unique identifier, the value the blacklist is keyed by.
     *
     * @param token must be a signed token from this service
     * @return the {@code jti} claim, distinct for every token ever minted here
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     expired
     */
    public String extractJti(String token) {
        return extractClaim(token, Claims::getId);
    }

    /**
     * Extracts whether this token has cleared 2FA or is still half-authenticated.
     *
     * <p>This is the claim the pre-auth path boundary is enforced on, so it is read on every
     * request that carries a token.
     *
     * @param token must be a signed token from this service
     * @return the token's declared type
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     expired
     * @throws IllegalArgumentException when the {@code token_type} claim holds a value this
     *     enum does not name, which is how a token minted by an older or forked issuer is
     *     rejected rather than silently treated as full authentication
     */
    public TokenType extractTokenType(String token) {
        String typeString = extractClaim(token, claims -> claims.get("token_type", String.class));
        return TokenType.valueOf(typeString);
    }

    /**
     * Reports whether a token is unexpired and was issued to the user it is being presented as.
     *
     * <p>Deliberately narrow: it compares the subject and checks expiry, and nothing more. It
     * does not consult the blacklist and does not look at the token type, so a caller relying on
     * this alone would accept a revoked token and would accept a {@code PRE_AUTH} token on a
     * fully-authenticated endpoint. Both of those checks live in {@code JwtAuthenticationFilter}
     * and must run alongside this one.
     *
     * @param token must be a signed token from this service
     * @param userDetails the identity loaded independently of the token, usually from the
     *     database, so a token naming a deleted user fails before this is reached
     * @return {@code true} only when the subject matches; a mismatch is the one case answered
     *     {@code false} rather than by an exception
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     expired, since parsing rejects an expired token before the expiry comparison is reached
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
    
    /**
     * Extracts the moment the token stops being accepted.
     *
     * <p>Used at logout to bound how long the blacklist entry has to be kept: once this passes,
     * the token is refused on its own and the row can be purged.
     *
     * @param token must be a signed token from this service, and must not have expired already,
     *     since parsing an expired token throws before its expiry can be read
     * @return the {@code exp} claim
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     expired
     */
    public Date extractExpirationDate(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extracts an arbitrary claim, verifying the signature in the process.
     *
     * <p>Every other extractor on this class routes through here, which is why reading any claim
     * at all is also a signature check: there is no path that returns claim data from a token
     * this service did not sign.
     *
     * @param token must be a signed token from this service
     * @param claimsResolver picks the value off the verified claim set; a claim that is absent
     *     yields {@code null} rather than throwing
     * @return whatever the resolver produced
     * @throws io.jsonwebtoken.JwtException when the signature does not verify or the token has
     *     expired
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}