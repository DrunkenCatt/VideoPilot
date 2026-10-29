package com.videopilot.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityCryptoAdapterTest {

    private final IdentityCryptoAdapter crypto = new IdentityCryptoAdapter();

    @Test
    void hashesPasswordsWithBcryptCostTwelve() {
        String encoded = crypto.encode("correct horse battery staple");

        assertThat(encoded).startsWith("$2a$12$");
        assertThat(encoded).doesNotContain("correct horse battery staple");
        assertThat(crypto.matches("correct horse battery staple", encoded)).isTrue();
        assertThat(crypto.matches("wrong", encoded)).isFalse();
        crypto.dummyCheck("unknown-user-password");
    }

    @Test
    void generatesOpaqueTokensAndStoresOnlyDeterministicDigests() {
        String first = crypto.generate();
        String second = crypto.generate();

        assertThat(first).isNotEqualTo(second).hasSizeGreaterThanOrEqualTo(40);
        assertThat(crypto.hash(first)).hasSize(64).doesNotContain(first);
        assertThat(crypto.hash(first)).isEqualTo(crypto.hash(first));
        assertThat(crypto.constantTimeEquals(first, first)).isTrue();
        assertThat(crypto.constantTimeEquals(first, second)).isFalse();
    }
}
