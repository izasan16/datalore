package com.datalore.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmCipherTest {

    private AesGcmCipher cipher() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new AesGcmCipher(key);
    }

    @Test
    void roundTrip() {
        var c = cipher();
        String enc = c.encrypt("p@ssw0rd中文");
        assertThat(enc).isNotEqualTo("p@ssw0rd中文");
        assertThat(c.decrypt(enc)).isEqualTo("p@ssw0rd中文");
    }

    @Test
    void sameInputDifferentCiphertext() {
        var c = cipher();
        assertThat(c.encrypt("abc")).isNotEqualTo(c.encrypt("abc"));
    }

    @Test
    void rejectsShortKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AesGcmCipher(shortKey))
                .isInstanceOf(IllegalStateException.class);
    }
}
