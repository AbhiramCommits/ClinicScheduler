package com.clinicscheduler.provider.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws io.jsonwebtoken.JwtException when the signature, issuer, or expiry is invalid.
     */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer("clinicscheduler")
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthUser(
                claims.getSubject(),
                claims.get("name", String.class),
                claims.get("role", String.class),
                claims.get("patientId", String.class));
    }
}
