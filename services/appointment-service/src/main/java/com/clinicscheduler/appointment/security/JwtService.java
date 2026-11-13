package com.clinicscheduler.appointment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String createToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.email())
                .issuer("clinicscheduler")
                .claim("name", user.name())
                .claim("role", user.role())
                .claim("patientId", user.patientId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
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

    public long expirationSeconds() {
        return expirationSeconds;
    }
}
