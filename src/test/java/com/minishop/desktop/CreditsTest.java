package com.minishop.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Credits} (QĐ11 of the D6 plan): the real {@code images/credits.tsv} and the parser on small strings. Plain
 * JUnit, no JavaFX.
 */
class CreditsTest {

    private static final String HEADER = "kind\tfile\tsubject\ttitle\tauthor\tlicense\tlicense_url\tsource\tsource_url";

    private static String row(String kind, String file) {
        return String.join("\t", kind, file, "Subject " + file, "Title", "Author", "CC0",
                "https://example.com/license", "Source", "https://example.com/source");
    }

    private static List<String> rawLines() throws IOException {
        try (InputStream in = CreditsTest.class.getResourceAsStream("/images/credits.tsv")) {
            assertThat(in).as("credits.tsv on the classpath").isNotNull();
            return Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }

    // ------------------------------------------------------------------ the real file

    @Test
    void load_readsAnIntroAnd21ImagesInFileOrderAndOneFontAndOneIcon() throws IOException {
        Credits credits = Credits.load();

        List<String> expectedFiles = rawLines().stream()
                .filter(line -> line.startsWith("image\t"))
                .map(line -> line.split("\t", -1)[1])
                .toList();
        assertThat(credits.intro()).isNotBlank();
        assertThat(credits.images()).hasSize(21)
                .extracting(Credits.Credit::file).containsExactlyElementsOf(expectedFiles);
        assertThat(credits.images()).allSatisfy(image -> {
            assertThat(image.kind()).isEqualTo("image");
            assertThat(image.subject()).isNotBlank();
            assertThat(image.author()).isNotBlank();
            assertThat(image.license()).isNotBlank();
        });
        assertThat(credits.fonts()).hasSize(1);
        assertThat(credits.fonts().get(0).file()).isEqualTo("PlusJakartaSans");
        assertThat(credits.icons()).hasSize(1);
        assertThat(credits.icons().get(0).file()).isEqualTo("app-icon.svg");
        assertThat(credits.icons().get(0).sourceUrl()).as("the icon is ours: no source link").isEmpty();
    }

    @Test
    void load_everyUrlIsEmptyOrHttps() {
        Credits credits = Credits.load();

        List<Credits.Credit> all = new ArrayList<>(credits.images());
        all.addAll(credits.fonts());
        all.addAll(credits.icons());
        assertThat(all).allSatisfy(credit -> {
            assertThat(credit.licenseUrl()).satisfiesAnyOf(
                    url -> assertThat(url).isEmpty(), url -> assertThat(FxExternalLinks.isHttps(url)).isTrue());
            assertThat(credit.sourceUrl()).satisfiesAnyOf(
                    url -> assertThat(url).isEmpty(), url -> assertThat(FxExternalLinks.isHttps(url)).isTrue());
        });
    }

    // ------------------------------------------------------------------ parse: good input

    @Test
    void parse_splitsRowsByKindKeepingFileOrder() {
        Credits credits = Credits.parse(HEADER + "\n" + row("image", "b.jpg") + "\n" + row("font", "F") + "\n"
                + row("image", "a.jpg") + "\n" + row("icon", "i.svg") + "\n" + row("font", "G") + "\n");

        assertThat(credits.images()).extracting(Credits.Credit::file).containsExactly("b.jpg", "a.jpg");
        assertThat(credits.fonts()).extracting(Credits.Credit::file).containsExactly("F", "G");
        assertThat(credits.icons()).extracting(Credits.Credit::file).containsExactly("i.svg");
        Credits.Credit first = credits.images().get(0);
        assertThat(first).isEqualTo(new Credits.Credit("image", "b.jpg", "Subject b.jpg", "Title", "Author", "CC0",
                "https://example.com/license", "Source", "https://example.com/source"));
    }

    @Test
    void parse_joinsIntroLinesWithASpaceAndSkipsEmptyOnes() {
        Credits credits = Credits.parse("# Dòng một\n#\n#   Dòng hai  \n" + HEADER + "\n");

        assertThat(credits.intro()).isEqualTo("Dòng một Dòng hai");
        assertThat(credits.images()).isEmpty();
    }

    @Test
    void parse_withoutIntro_hasAnEmptyIntro() {
        assertThat(Credits.parse(HEADER + "\n" + row("image", "a.jpg")).intro()).isEmpty();
    }

    @Test
    void parse_toleratesABomBlankLinesAndWindowsLineEndings() {
        String tsv = "﻿# Intro\r\n\r\n" + HEADER + "\r\n\r\n" + row("image", "a.jpg") + "\r\n   \r\n"
                + row("icon", "i.svg") + "\r\n";

        Credits credits = Credits.parse(tsv);

        assertThat(credits.intro()).isEqualTo("Intro");
        assertThat(credits.images()).hasSize(1);
        assertThat(credits.icons()).hasSize(1);
    }

    @Test
    void parse_toleratesABomBeforeTheHeaderWhenThereIsNoIntro() {
        Credits credits = Credits.parse("﻿" + HEADER + "\n" + row("image", "a.jpg") + "\n");

        assertThat(credits.images()).hasSize(1);
    }

    @Test
    void parse_keepsEmptyTrailingColumns() {
        Credits credits = Credits.parse(HEADER + "\nicon\ti.svg\tIcon\tT\tA\tCC0\t\tSource\t\n");

        Credits.Credit icon = credits.icons().get(0);
        assertThat(icon.licenseUrl()).isEmpty();
        assertThat(icon.sourceUrl()).isEmpty();
        assertThat(icon.source()).isEqualTo("Source");
    }

    @Test
    void parse_returnsListsThatCannotBeChanged() {
        Credits credits = Credits.parse(HEADER + "\n" + row("image", "a.jpg"));

        assertThatThrownBy(() -> credits.images().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> credits.fonts().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> credits.icons().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ parse: bad input

    @Test
    void parse_rowWithTooFewColumns_throwsWithLineNumberAndCounts() {
        String tsv = "# Intro\n" + HEADER + "\n" + row("image", "a.jpg") + "\nimage\tsecret-file.jpg\tonly three\n";

        assertThatIllegalStateException().isThrownBy(() -> Credits.parse(tsv))
                .withMessageContaining("dòng 4")
                .withMessageContaining("3 cột")
                .withMessageContaining("9")
                .withMessageNotContaining("secret-file");
    }

    @Test
    void parse_rowWithTooManyColumns_throws() {
        String tsv = HEADER + "\n" + row("image", "a.jpg") + "\textra-secret\n";

        assertThatIllegalStateException().isThrownBy(() -> Credits.parse(tsv))
                .withMessageContaining("dòng 2")
                .withMessageContaining("10 cột")
                .withMessageNotContaining("extra-secret");
    }

    @Test
    void parse_unknownKind_throwsWithoutEchoingTheKind() {
        String tsv = HEADER + "\n" + row("secretkind", "a.jpg") + "\n";

        assertThatIllegalStateException().isThrownBy(() -> Credits.parse(tsv))
                .withMessageContaining("dòng 2")
                .withMessageNotContaining("secretkind")
                .withMessageNotContaining("a.jpg");
    }

    @Test
    void parse_kindIsCaseSensitive() {
        assertThatIllegalStateException().isThrownBy(() -> Credits.parse(HEADER + "\n" + row("Image", "a.jpg")));
    }

    @Test
    void parse_missingHeader_throws() {
        String tsv = "# Intro\n" + row("image", "a.jpg") + "\n";

        assertThatIllegalStateException().isThrownBy(() -> Credits.parse(tsv))
                .withMessageContaining("dòng 2")
                .withMessageNotContaining("a.jpg");
    }
}
