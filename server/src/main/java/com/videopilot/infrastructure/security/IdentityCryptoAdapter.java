package com.videopilot.infrastructure.security;

import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class IdentityCryptoAdapter implements PasswordHash, SecureToken {

    private static final int TOKEN_BYTES = 32;

    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());

    @Override
    public String encode(String rawPassword) {
        return passwords.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return passwords.matches(rawPassword, encodedPassword);
    }

    @Override
    public void dummyCheck(String rawPassword) {
        passwords.matches(rawPassword, dummyPasswordHash);
    }

    @Override
    public String generate() {
        byte[] value = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Override
    public String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    @Override
    public boolean constantTimeEquals(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
