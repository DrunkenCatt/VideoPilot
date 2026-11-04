package com.videopilot.context.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContextInputFingerprint {

    private ContextInputFingerprint() {
    }

    public static String sha256(ContextWorkSpec spec) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        add(digest, spec.mediaSha256().toLowerCase());
        add(digest, spec.extractionVersion());
        add(digest, spec.alignmentVersion());
        add(digest, spec.providerVersion());
        add(digest, spec.kind().name());
        add(digest, Long.toString(spec.startMs()));
        add(digest, Long.toString(spec.endMs()));
        spec.parameters().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    add(digest, entry.getKey());
                    add(digest, entry.getValue());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
