package com.minishop.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageSizes}: reading the original size of an image from its header, so the loader can ask for a cover size
 * (QĐ8 of the D3 plan). Plain Java, no JavaFX toolkit; nothing here may throw, whatever the file holds.
 */
class ImageSizesTest {

    private static final int[] UNKNOWN = {0, 0};

    @TempDir
    Path dir;

    private Path png(String name, int width, int height) throws IOException {
        Path file = dir.resolve(name);
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    // ------------------------------------------------------------------ probe

    @Test
    void probe_aSeedJpgOnTheClasspath_givesItsTrueSize() throws IOException {
        URL url = ImageSizesTest.class.getResource("/images/products/ao-thun-cotton-co-tron.jpg");
        assertThat(url).as("seed image on the classpath").isNotNull();
        BufferedImage expected;
        try (InputStream in = url.openStream()) {
            expected = ImageIO.read(in);
        }

        int[] size = ImageSizes.probe(url.toString());

        assertThat(size).containsExactly(expected.getWidth(), expected.getHeight());
        assertThat(size[0]).isPositive();
        assertThat(size[1]).isPositive();
    }

    @Test
    void probe_aPngFile_givesItsSize() throws IOException {
        Path file = png("a.png", 30, 20);

        assertThat(ImageSizes.probe(file.toUri().toString())).containsExactly(30, 20);
    }

    @Test
    void probe_anImageInsideAJar_givesItsSize() throws IOException {
        Path source = png("inner.png", 17, 41);
        // The jar URL handler caches the open JarFile, and Windows cannot delete an open file, so this jar lives
        // under target/ (removed by "mvn clean") and not in the @TempDir, whose cleanup would fail.
        Path jarDir = Files.createDirectories(Path.of("target", "test-image-sizes"));
        Path jar = jarDir.resolve("images-" + System.nanoTime() + ".jar").toAbsolutePath();
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("images/inner.png"));
            zip.write(Files.readAllBytes(source));
            zip.closeEntry();
        }

        assertThat(ImageSizes.probe("jar:" + jar.toUri() + "!/images/inner.png")).containsExactly(17, 41);
    }

    @Test
    void probe_theSchemeIsMatchedIgnoringCase() throws IOException {
        Path file = png("a.png", 8, 9);
        String url = file.toUri().toString().replaceFirst("^file:", "FILE:");

        assertThat(ImageSizes.probe(url)).containsExactly(8, 9);
    }

    @Test
    void probe_doesNotReadTheWholeImage() throws IOException {
        // A valid header with a cut body still gives the size: only the header is read.
        Path file = png("big.png", 300, 200);
        byte[] bytes = Files.readAllBytes(file);
        Path cut = dir.resolve("cut.png");
        Files.write(cut, Arrays.copyOf(bytes, Math.min(bytes.length, 64)));

        assertThat(ImageSizes.probe(cut.toUri().toString())).containsExactly(300, 200);
    }

    @Test
    void probe_aGarbageFile_returnsUnknownWithoutThrowing() throws IOException {
        Path file = dir.resolve("garbage.jpg");
        Files.writeString(file, "this is not an image at all", StandardCharsets.UTF_8);

        assertThat(ImageSizes.probe(file.toUri().toString())).containsExactly(UNKNOWN);
    }

    @Test
    void probe_anEmptyFile_returnsUnknown() throws IOException {
        Path file = dir.resolve("empty.png");
        Files.write(file, new byte[0]);

        assertThat(ImageSizes.probe(file.toUri().toString())).containsExactly(UNKNOWN);
    }

    @Test
    void probe_aMissingFile_returnsUnknown() {
        assertThat(ImageSizes.probe(dir.resolve("missing.png").toUri().toString())).containsExactly(UNKNOWN);
    }

    @Test
    void probe_aMissingEntryInsideAJar_returnsUnknown() {
        assertThat(ImageSizes.probe("jar:" + dir.resolve("none.jar").toUri() + "!/x.png")).containsExactly(UNKNOWN);
    }

    @Test
    void probe_anUnsupportedScheme_returnsUnknownWithoutAnyNetworkAccess() {
        assertThat(ImageSizes.probe("https://example.invalid/a.png")).containsExactly(UNKNOWN);
        assertThat(ImageSizes.probe("http://example.invalid/a.png")).containsExactly(UNKNOWN);
        assertThat(ImageSizes.probe("data:image/png;base64,AAAA")).containsExactly(UNKNOWN);
    }

    @Test
    void probe_aMalformedUrl_returnsUnknownWithoutThrowing() {
        assertThat(ImageSizes.probe("file:///%%%")).containsExactly(UNKNOWN);
        assertThat(ImageSizes.probe("jar:not a url")).containsExactly(UNKNOWN);
    }

    // ------------------------------------------------------------------ coverRequest

    @Test
    void coverRequest_ofALargerWideImage_makesTheShorterSideEqualTheBox() {
        assertThat(ImageSizes.coverRequest(new int[] {600, 400}, 100, 100)).containsExactly(150, 100);
    }

    @Test
    void coverRequest_ofALargerTallImage_makesTheShorterSideEqualTheBox() {
        assertThat(ImageSizes.coverRequest(new int[] {400, 600}, 100, 100)).containsExactly(100, 150);
    }

    @Test
    void coverRequest_ofAnImageNotLargerThanTheBox_asksForTheOriginalSize() {
        assertThat(ImageSizes.coverRequest(new int[] {50, 50}, 100, 100)).containsExactly(0, 0);
        assertThat(ImageSizes.coverRequest(new int[] {100, 100}, 100, 100)).containsExactly(0, 0);
    }

    @Test
    void coverRequest_ofAnUnknownSize_asksForTheBox() {
        assertThat(ImageSizes.coverRequest(UNKNOWN, 120, 80)).containsExactly(120, 80);
    }
}
