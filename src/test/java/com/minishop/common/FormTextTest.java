package com.minishop.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

/** Text cleaning for every form text field (AC-007-14, AC-007-15; BR-007-6). Plain JUnit, no Spring. */
class FormTextTest {

    // ---------- invisible characters and special spaces (AC-007-14) ----------

    /** AC-007-14: each of these alone, in both kinds of field, leaves nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"​", "‌", "‍", "⁠", "﻿", " ", "­", "‮", " ",
            " "})
    void clean_singleInvisibleOrSpecialSpaceCharacter_becomesBlank(String text) {
        assertThat(FormText.clean(text, false)).isEmpty();
        assertThat(FormText.clean(text, true)).isEmpty();
    }

    /** AC-007-14: mixed with normal spaces, still nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"​ ‌", "   ", "​‌‍⁠﻿ ", "  ﻿     "})
    void clean_onlyInvisibleCharactersAndSpaces_becomesBlank(String text) {
        assertThat(FormText.clean(text, false)).isEmpty();
        assertThat(FormText.clean(text, true)).isEmpty();
    }

    /** AC-007-15 */
    @Test
    void clean_invisibleCharacterBetweenWords_isDropped() {
        assertThat(FormText.clean("Áo​ thun", false)).isEqualTo("Áo thun");
        assertThat(FormText.clean("Áo​ thun", true)).isEqualTo("Áo thun");
    }

    @Test
    void clean_invisibleCharacterInsideAWord_joinsTheLetters() {
        assertThat(FormText.clean("Áo‍thun", false)).isEqualTo("Áothun");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " ", " ", " ", "　"})
    void clean_specialSpace_becomesOneNormalSpace(String space) {
        assertThat(FormText.clean("Áo" + space + "thun", false)).isEqualTo("Áo thun");
    }

    @Test
    void clean_specialSpacesAtTheEnds_areStripped() {
        assertThat(FormText.clean(" 1 ", false)).isEqualTo("1");
        assertThat(FormText.clean("  Áo thun  ", false)).isEqualTo("Áo thun");
    }

    /** BR-007-6: the right-to-left override is a format character and is removed. */
    @Test
    void clean_bidiControlCharacters_areRemoved() {
        assertThat(FormText.clean("abc‮def‬", false)).isEqualTo("abcdef");
    }

    // ---------- NFC (AC-007-15) ----------

    @Test
    void clean_decomposedText_becomesPrecomposed() {
        String nfd = Normalizer.normalize("Đồ chơi", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("Đồ chơi");

        String cleaned = FormText.clean(nfd, false);

        assertThat(cleaned).isEqualTo("Đồ chơi");
        assertThat(Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)).isTrue();
    }

    /** The filter runs before NFC, so an invisible character between a letter and its accent still composes. */
    @Test
    void clean_invisibleCharacterBetweenLetterAndAccent_stillComposes() {
        assertThat(FormText.clean("e​́", false)).isEqualTo("é");
    }

    @Test
    void clean_fullVietnameseSentence_isUnchanged() {
        String sentence = "Nguyễn Thị Hồng Ánh: Đường Lê Lợi, Quận 1 - ưu đãi 50% cho khách hàng thân thiết!";

        assertThat(FormText.clean(sentence, false)).isEqualTo(sentence);
        assertThat(FormText.clean(sentence, true)).isEqualTo(sentence);
    }

    // ---------- control characters ----------

    @Test
    void clean_nulCharacter_isRemovedSoThatPostgresAcceptsTheText() {
        assertThat(FormText.clean("\u0000 abc", false)).isEqualTo("abc");
        assertThat(FormText.clean("\u0000 abc", true)).isEqualTo("abc");
        assertThat(FormText.clean("a\u0000b", false)).isEqualTo("ab");
    }

    @Test
    void clean_otherControlCharacters_areRemovedEvenInMultilineText() {
        assertThat(FormText.clean("a\u0007b\u001Bc\u007Fd\u0085e", true)).isEqualTo("abcde");
        assertThat(FormText.clean("a\u0007b\u001Bc\u007Fd\u0085e", false)).isEqualTo("abcde");
    }

    // ---------- single-line vs multi-line ----------

    @Test
    void clean_singleLine_removesNewlinesAndTabs() {
        assertThat(FormText.clean("a\nb\tc\r\nd\re", false)).isEqualTo("abcde");
    }

    @Test
    void clean_multiline_keepsNewlinesAndTabs() {
        assertThat(FormText.clean("dòng 1\n\tdòng 2", true)).isEqualTo("dòng 1\n\tdòng 2");
    }

    /** BR-007-6: {@code \r\n} and a lone {@code \r} both become {@code \n} in a multi-line field. */
    @Test
    void clean_multiline_convertsCrLfAndLoneCrToLf() {
        assertThat(FormText.clean("a\r\nb\rc\nd", true)).isEqualTo("a\nb\nc\nd");
        assertThat(FormText.clean("a\r\n\r\nb", true)).isEqualTo("a\n\nb");
        assertThat(FormText.clean("a\r\rb", true)).isEqualTo("a\n\nb");
    }

    @Test
    void clean_multiline_stripsTheEndsButNotTheMiddleLines() {
        assertThat(FormText.clean("\r\n  dòng 1\r\n  dòng 2  \r\n\t", true)).isEqualTo("dòng 1\n  dòng 2");
    }

    @Test
    void clean_multiline_textOfOnlyLineBreaksAndTabs_becomesBlank() {
        assertThat(FormText.clean("\r\n\t\n \r", true)).isEmpty();
    }

    @Test
    void clean_singleLine_stripsPlainSpacesAtTheEnds() {
        assertThat(FormText.clean("  Nguyễn Văn An  ", false)).isEqualTo("Nguyễn Văn An");
    }

    // ---------- null and empty ----------

    @ParameterizedTest
    @NullSource
    void clean_null_staysNull(String value) {
        assertThat(FormText.clean(value, false)).isNull();
        assertThat(FormText.clean(value, true)).isNull();
    }

    @Test
    void clean_emptyString_staysEmpty() {
        assertThat(FormText.clean("", false)).isEmpty();
        assertThat(FormText.clean("", true)).isEmpty();
    }

    // ---------- blankToNull ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\n\t "})
    void blankToNull_nullOrBlank_returnsNull(String value) {
        assertThat(FormText.blankToNull(value)).isNull();
    }

    @Test
    void blankToNull_text_isReturnedAsIs() {
        assertThat(FormText.blankToNull("ghi chú")).isEqualTo("ghi chú");
        assertThat(FormText.blankToNull(" a ")).isEqualTo(" a ");
    }

    /** The usual combination in a form record: invisible-only input ends up as {@code null}. */
    @Test
    void blankToNull_afterCleaningInvisibleOnlyText_returnsNull() {
        assertThat(FormText.blankToNull(FormText.clean("​ ﻿", true))).isNull();
    }
}
