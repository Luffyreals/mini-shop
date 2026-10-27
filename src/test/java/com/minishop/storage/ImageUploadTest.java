package com.minishop.storage;

import com.minishop.storage.InvalidImageException.Reason;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Image validation (AC-006-27, 28, 29; BR-006-10): extension, content type and magic bytes must agree. */
class ImageUploadTest {

    private static InputStreamSource of(byte[] bytes) {
        return new ByteArrayResource(bytes);
    }

    private static ImageUpload accept(String filename, String contentType, byte[] bytes) {
        Optional<ImageUpload> upload = ImageUpload.from(filename, contentType, bytes.length, of(bytes));
        assertThat(upload).isPresent();
        return upload.get();
    }

    private static Reason rejectReason(String filename, String contentType, byte[] bytes) {
        return rejectReason(filename, contentType, bytes.length, of(bytes));
    }

    private static Reason rejectReason(String filename, String contentType, long size, InputStreamSource content) {
        try {
            ImageUpload.from(filename, contentType, size, content);
        } catch (InvalidImageException e) {
            return e.reason();
        }
        throw new AssertionError("Expected InvalidImageException");
    }

    // ---------- valid images ----------

    @Test
    void from_validJpg_isJpeg() {
        assertThat(accept("photo.jpg", "image/jpeg", TestImages.jpeg(100)).format()).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    void from_jpegExtension_isJpeg() {
        assertThat(accept("photo.jpeg", "image/jpeg", TestImages.jpeg(100)).format()).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    void from_validPng_isPng() {
        assertThat(accept("photo.png", "image/png", TestImages.png(100)).format()).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void from_validWebp_isWebp() {
        assertThat(accept("photo.webp", "image/webp", TestImages.webp(100)).format()).isEqualTo(ImageFormat.WEBP);
    }

    @Test
    void from_keepsTheBytesAndSize() {
        byte[] data = TestImages.png(500);

        ImageUpload upload = accept("a.png", "image/png", data);

        assertThat(upload.bytes()).isEqualTo(data);
        assertThat(upload.size()).isEqualTo(500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ảnh sản phẩm (1).PNG", "../../x.png", "..\\..\\x.png", "a.b.c.png", "ẢNH.PnG"})
    void from_anyOriginalNameWithPngExtension_isAccepted(String filename) {
        assertThat(accept(filename, "image/png", TestImages.png(100)).format()).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void from_contentTypeWithParametersAndUpperCase_isAccepted() {
        assertThat(accept("a.png", "IMAGE/PNG; charset=binary", TestImages.png(100)).format())
                .isEqualTo(ImageFormat.PNG);
    }

    // ---------- unsupported ----------

    @ParameterizedTest
    @ValueSource(strings = {"a.gif", "a.svg", "a.html", "a.exe", "noextension", "a.", "a.png.exe", ".htaccess"})
    void from_otherExtension_isUnsupportedType(String filename) {
        assertThat(rejectReason(filename, "image/png", TestImages.png(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_realGifRenamedToGif_isUnsupportedType() {
        assertThat(rejectReason("a.gif", "image/gif", TestImages.gif())).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_svg_isUnsupportedType() {
        assertThat(rejectReason("a.svg", "image/svg+xml", TestImages.svg())).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_htmlRenamedToJpgWithImageContentType_isUnsupportedType() {
        assertThat(rejectReason("evil.jpg", "image/jpeg", TestImages.htmlNamedJpg()))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_gifBytesNamedPng_isUnsupportedType() {
        assertThat(rejectReason("a.png", "image/png", TestImages.gif())).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_pngBytesWithJpgExtension_isUnsupportedType() {
        assertThat(rejectReason("a.jpg", "image/jpeg", TestImages.png(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_jpegBytesWithPngExtensionAndPngContentType_isUnsupportedType() {
        assertThat(rejectReason("a.png", "image/png", TestImages.jpeg(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_validPngWithTextHtmlContentType_isUnsupportedType() {
        assertThat(rejectReason("a.png", "text/html", TestImages.png(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_validPngWithContentTypeOfAnotherImageFormat_isUnsupportedType() {
        assertThat(rejectReason("a.png", "image/jpeg", TestImages.png(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_nullContentType_isUnsupportedType() {
        assertThat(rejectReason("a.png", null, TestImages.png(100))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_riffThatIsNotWebp_isUnsupportedType() {
        byte[] wav = TestImages.webp(100);
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';

        assertThat(rejectReason("a.webp", "image/webp", wav)).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_emptyContentWithAName_isUnsupportedType() {
        assertThat(rejectReason("a.png", "image/png", new byte[0])).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void from_truncatedPngSignature_isUnsupportedType() {
        assertThat(rejectReason("a.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    // ---------- size ----------

    @Test
    void from_exactlyTwoMebibytes_isAccepted() {
        assertThat(accept("a.png", "image/png", TestImages.png(2 * 1024 * 1024)).size()).isEqualTo(2_097_152);
    }

    @Test
    void from_oneByteOverTwoMebibytes_isTooLargeWithoutReadingTheContent() {
        InputStreamSource content = mock(InputStreamSource.class);

        assertThat(rejectReason("a.png", "image/png", 2_097_153, content)).isEqualTo(Reason.TOO_LARGE);

        verifyNoInteractions(content);
    }

    @Test
    void from_twoAndAHalfMegabytes_isTooLargeWithoutReadingTheContent() {
        InputStreamSource content = mock(InputStreamSource.class);

        assertThat(rejectReason("a.png", "image/png", 2_500_000, content)).isEqualTo(Reason.TOO_LARGE);

        verifyNoInteractions(content);
    }

    @Test
    void from_nineMegabytes_isTooLarge() {
        InputStreamSource content = mock(InputStreamSource.class);

        assertThat(rejectReason("a.png", "image/png", 9L * 1024 * 1024, content)).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void from_tooLargeTakesPriorityOverUnsupportedType() {
        InputStreamSource content = mock(InputStreamSource.class);

        assertThat(rejectReason("a.gif", "image/gif", 3_000_000, content)).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void from_declaredSizeSmallButContentLarger_isTooLarge() {
        byte[] big = TestImages.png(2 * 1024 * 1024 + 10);
        InputStreamSource content = () -> new ByteArrayInputStream(big);

        assertThat(rejectReason("a.png", "image/png", 100, content)).isEqualTo(Reason.TOO_LARGE);
    }

    // ---------- nothing selected ----------

    @Test
    void from_sizeZeroAndNoName_isEmpty() {
        InputStreamSource content = mock(InputStreamSource.class);

        assertThat(ImageUpload.from("", "application/octet-stream", 0, content)).isEmpty();
        assertThat(ImageUpload.from(null, null, 0, content)).isEmpty();
        assertThat(ImageUpload.from("   ", "", 0, content)).isEmpty();
        verifyNoInteractions(content);
    }

    @Test
    void from_sizeZeroButWithAName_isRejectedNotIgnored() {
        assertThatThrownBy(() -> ImageUpload.from("a.png", "image/png", 0, of(new byte[0])))
                .isInstanceOf(InvalidImageException.class);
    }

    // ---------- fromFile (a file chosen on the desktop, QĐ6 of the D5 plan) ----------

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    /** A file of this size that is never filled with real bytes (sparse where the file system allows it). */
    private static Path writeBig(Path dir, String name, long size) throws IOException {
        Path file = dir.resolve(name);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
        }
        return file;
    }

    private static Reason fileRejectReason(Path file) {
        try {
            ImageUpload.fromFile(file);
        } catch (InvalidImageException e) {
            return e.reason();
        }
        throw new AssertionError("Expected InvalidImageException");
    }

    @Test
    void fromFile_validJpg_isJpegWithTheBytesOfTheFile(@TempDir Path dir) throws IOException {
        byte[] data = TestImages.jpeg(300);

        ImageUpload upload = ImageUpload.fromFile(write(dir, "photo.jpg", data));

        assertThat(upload.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(upload.size()).isEqualTo(300);
        assertThat(upload.bytes()).isEqualTo(data);
    }

    @Test
    void fromFile_validPng_isPng(@TempDir Path dir) throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, "photo.png", TestImages.png(300))).format())
                .isEqualTo(ImageFormat.PNG);
    }

    /** Where the OS does not know {@code .webp}, {@code probeContentType} gives null; the extension takes over. */
    @Test
    void fromFile_validWebp_isWebp(@TempDir Path dir) throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, "photo.webp", TestImages.webp(300))).format())
                .isEqualTo(ImageFormat.WEBP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.jpeg", "PHOTO.JPG", "photo.JPEG", "ảnh sản phẩm (1).JPG", "a.b.c.jpg"})
    void fromFile_jpegWithOtherNames_isAccepted(String name, @TempDir Path dir) throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, name, TestImages.jpeg(100))).format()).isEqualTo(ImageFormat.JPEG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ảnh sản phẩm (1).PNG", "ẢNH (2).Png", "photo.PNG"})
    void fromFile_pngWithUpperCaseExtensionAccentsAndCopyNumber_isAccepted(String name, @TempDir Path dir)
            throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, name, TestImages.png(100))).format()).isEqualTo(ImageFormat.PNG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.WEBP", "photo.WebP"})
    void fromFile_webpWithUpperCaseExtension_isAccepted(String name, @TempDir Path dir) throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, name, TestImages.webp(100))).format()).isEqualTo(ImageFormat.WEBP);
    }

    @Test
    void fromFile_exactlyTwoMebibytes_isAccepted(@TempDir Path dir) throws IOException {
        assertThat(ImageUpload.fromFile(write(dir, "a.png", TestImages.png(2 * 1024 * 1024))).size())
                .isEqualTo(2_097_152);
    }

    @Test
    void fromFile_gif_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "a.gif", TestImages.gif()))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_svg_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "a.svg", TestImages.svg()))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_htmlRenamedToJpg_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "evil.jpg", TestImages.htmlNamedJpg())))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_pngBytesNamedJpg_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "a.jpg", TestImages.png(100)))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_withoutExtension_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "noextension", TestImages.png(100))))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_emptyFile_isUnsupportedType(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(write(dir, "a.png", new byte[0]))).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void fromFile_pathWithoutAFileName_isUnsupportedType(@TempDir Path dir) {
        assertThat(fileRejectReason(dir.getRoot())).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @ParameterizedTest
    @ValueSource(longs = {2_097_153L, 2_500_000L, 9L * 1024 * 1024})
    void fromFile_overTwoMebibytes_isTooLarge(long size, @TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(writeBig(dir, "big.png", size))).isEqualTo(Reason.TOO_LARGE);
    }

    /**
     * The size is read from the file system first: a big file with an unsupported extension is "too large", which is
     * the answer only when the size is checked before the extension, the type and the content (AC-006-29).
     */
    @Test
    void fromFile_tooLargeTakesPriorityOverEveryOtherCheck(@TempDir Path dir) throws IOException {
        assertThat(fileRejectReason(writeBig(dir, "big.gif", 2_500_000))).isEqualTo(Reason.TOO_LARGE);
        assertThat(fileRejectReason(writeBig(dir, "big", 9L * 1024 * 1024))).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void fromFile_nullPath_throwsNullPointerException() {
        assertThatThrownBy(() -> ImageUpload.fromFile(null)).isInstanceOf(NullPointerException.class);
    }

    /** The message goes to the error log; it must not carry the user's folder or file name. */
    @Test
    void fromFile_missingFile_throwsUncheckedIoExceptionWithoutThePath(@TempDir Path dir) {
        Path missing = dir.resolve("secret-folder-ảnh").resolve("missing-secret-name.png");

        Throwable failure = catchThrowable(() -> ImageUpload.fromFile(missing));

        assertThat(failure).isInstanceOf(UncheckedIOException.class);
        assertNoPathInTheChain(failure, dir, "missing-secret-name", "secret-folder");
    }

    @Test
    void fromFile_directoryNamedLikeAnImage_throwsUncheckedIoExceptionWithoutThePath(@TempDir Path dir)
            throws IOException {
        Path directory = Files.createDirectory(dir.resolve("folder-secret.png"));

        Throwable failure = catchThrowable(() -> ImageUpload.fromFile(directory));

        assertThat(failure).isInstanceOf(UncheckedIOException.class);
        assertNoPathInTheChain(failure, dir, "folder-secret");
    }

    private static void assertNoPathInTheChain(Throwable failure, Path dir, String... fragments) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String text = String.valueOf(t.getMessage());
            assertThat(text).doesNotContain(dir.toString()).doesNotContain(dir.getFileName().toString());
            for (String fragment : fragments) {
                assertThat(text).doesNotContain(fragment);
            }
            assertThat(t.getSuppressed()).isEmpty();
        }
    }

    // ---------- toString ----------

    @Test
    void toString_showsOnlyFormatAndSize() {
        ImageUpload upload = accept("secret-name.png", "image/png", TestImages.png(64));

        assertThat(upload.toString()).isEqualTo("ImageUpload[format=PNG, size=64]")
                .doesNotContain("secret-name");
    }
}
