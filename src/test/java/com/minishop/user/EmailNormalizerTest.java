package com.minishop.user;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Email normalisation shared by registration and login (BR-003-1, BR-007-6). Plain JUnit, no Spring. */
class EmailNormalizerTest {

    @Test
    void normalize_trimsSpecialSpacesAndLowerCases() {
        assertThat(EmailNormalizer.normalize(" User@Example.COM ")).isEqualTo("user@example.com");
    }

    @Test
    void normalize_dropsInvisibleCharacters() {
        assertThat(EmailNormalizer.normalize("a​@b.c")).isEqualTo("a@b.c");
        assertThat(EmailNormalizer.normalize("﻿a@b.c")).isEqualTo("a@b.c");
    }

    @Test
    void normalize_null_returnsEmptyString() {
        assertThat(EmailNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalize_onlyInvisibleCharacters_returnsEmptyString() {
        assertThat(EmailNormalizer.normalize("​ ")).isEmpty();
    }

    /** Registration and login must compare the very same string. */
    @Test
    void normalize_givesTheSameResultAsTheRegistrationForm() {
        String typed = "  An@Example.COM ";

        String fromForm = new RegistrationForm("An", typed, "abcdefg1", "abcdefg1").email().toLowerCase(Locale.ROOT);

        assertThat(EmailNormalizer.normalize(typed)).isEqualTo(fromForm).isEqualTo("an@example.com");
    }

    /** {@link Locale#ROOT}: the Turkish dotless-i rules must not apply, whatever the JVM default locale is. */
    @Test
    void normalize_usesRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(EmailNormalizer.normalize("INFO@Example.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
