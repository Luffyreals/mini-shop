package com.minishop.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QĐ10: each bundled TTF must draw all Vietnamese letters (upper and lower case) and the dong sign, because JavaFX
 * silently falls back to another font for a missing glyph and the design would break without any error.
 * Uses {@code java.awt.Font}, which needs no JavaFX toolkit and no display.
 */
class FontCoverageTest {

    private static final String VIETNAMESE_UPPER =
            "ÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬĐÈÉẺẼẸÊỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢÙÚỦŨỤƯỪỨỬỮỰỲÝỶỸỴ";
    private static final String VIETNAMESE_LOWER = VIETNAMESE_UPPER.toLowerCase(java.util.Locale.ROOT);
    private static final String DONG_SIGN = "₫";
    private static final String BASIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;!?+-/()%";

    static List<String> fontResources() {
        return Theme.FONT_RESOURCES;
    }

    private static Font load(String resource) throws IOException, FontFormatException {
        try (InputStream in = FontCoverageTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("resource %s", resource).isNotNull();
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    @Test
    void themeBundlesFourStaticWeights() {
        assertThat(Theme.FONT_RESOURCES).containsExactly(
                "/ui/fonts/PlusJakartaSans-Regular.ttf", "/ui/fonts/PlusJakartaSans-Medium.ttf",
                "/ui/fonts/PlusJakartaSans-SemiBold.ttf", "/ui/fonts/PlusJakartaSans-Bold.ttf");
    }

    @ParameterizedTest
    @MethodSource("fontResources")
    void font_canDisplayEveryUppercaseVietnameseVowelWithDiacritics(String resource) throws Exception {
        assertThat(load(resource).canDisplayUpTo(VIETNAMESE_UPPER)).as("first missing index").isEqualTo(-1);
    }

    @ParameterizedTest
    @MethodSource("fontResources")
    void font_canDisplayEveryLowercaseVietnameseVowelWithDiacritics(String resource) throws Exception {
        assertThat(VIETNAMESE_LOWER).isNotEqualTo(VIETNAMESE_UPPER).contains("đ", "ơ", "ư", "ặ", "ỵ");
        assertThat(load(resource).canDisplayUpTo(VIETNAMESE_LOWER)).as("first missing index").isEqualTo(-1);
    }

    @ParameterizedTest
    @MethodSource("fontResources")
    void font_canDisplayTheDongSign(String resource) throws Exception {
        assertThat(load(resource).canDisplayUpTo(DONG_SIGN)).as("dong sign").isEqualTo(-1);
    }

    @ParameterizedTest
    @MethodSource("fontResources")
    void font_canDisplayAsciiLettersDigitsAndPunctuation(String resource) throws Exception {
        assertThat(load(resource).canDisplayUpTo(BASIC)).isEqualTo(-1);
    }

    /**
     * theme.css depends on these exact family names: Medium and SemiBold are families of their own, because
     * JavaFX does not group them with Regular and Bold (measured, see the opening comment of theme.css).
     */
    @Test
    void fonts_haveTheFamilyNamesThatThemeCssUses() throws Exception {
        assertThat(load("/ui/fonts/PlusJakartaSans-Regular.ttf").getFamily()).isEqualTo("Plus Jakarta Sans");
        assertThat(load("/ui/fonts/PlusJakartaSans-Bold.ttf").getFamily()).isEqualTo("Plus Jakarta Sans");
        assertThat(load("/ui/fonts/PlusJakartaSans-Medium.ttf").getFamily()).isEqualTo("Plus Jakarta Sans Medium");
        assertThat(load("/ui/fonts/PlusJakartaSans-SemiBold.ttf").getFamily())
                .isEqualTo("Plus Jakarta Sans SemiBold");
    }
}
