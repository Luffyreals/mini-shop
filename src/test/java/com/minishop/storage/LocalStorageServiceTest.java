package com.minishop.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.minishop.support.LogCapture;
import com.minishop.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local storage (AC-006-27, 30; BR-006-10, 11): UUID names, never overwrite, stay inside the root, clean up after
 * rollback, delete old files only after commit and only when they are uploads.
 */
class LocalStorageServiceTest {

    private static final Pattern STORED_URL = Pattern.compile(
            "^/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    @TempDir
    Path tempDir;

    Path root;
    LocalStorageService storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("uploads");
        storage = new LocalStorageService(root.toString());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- helpers ----------

    private static ImageUpload png(String originalName, int size) {
        byte[] data = TestImages.png(size);
        return ImageUpload.from(originalName, "image/png", data.length, new ByteArrayResource(data)).orElseThrow();
    }

    private static ImageUpload upload(String name, String contentType, byte[] data) {
        return ImageUpload.from(name, contentType, data.length, new ByteArrayResource(data)).orElseThrow();
    }

    private List<Path> filesInRoot() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.toList();
        }
    }

    private Path fileOf(String url) {
        return root.resolve(url.substring("/uploads/".length()));
    }

    private static void completeWith(int status) {
        List<TransactionSynchronization> syncs = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            syncs.forEach(TransactionSynchronization::afterCommit);
        }
        syncs.forEach(s -> s.afterCompletion(status));
    }

    // ---------- construction ----------

    @Test
    void constructor_createsTheUploadDirectoryAndNormalizesIt() throws IOException {
        Path nested = tempDir.resolve("a").resolve("..").resolve("b").resolve("uploads");

        LocalStorageService created = new LocalStorageService(nested.toString());

        assertThat(created.root()).isEqualTo(tempDir.resolve("b").resolve("uploads").toAbsolutePath().normalize());
        assertThat(created.root()).isDirectory();
    }

    // ---------- store ----------

    @Test
    void store_writesOneUuidNamedFileWithNormalizedExtensionAndReturnsItsUrl() throws IOException {
        ImageUpload image = png("ảnh sản phẩm (1).PNG", 300);

        String url = storage.store(image);

        assertThat(url).matches(STORED_URL).endsWith(".png");
        assertThat(url.length()).isLessThan(60);
        assertThat(filesInRoot()).hasSize(1);
        assertThat(filesInRoot().get(0).getFileName().toString()).isEqualTo(url.substring("/uploads/".length()));
        assertThat(Files.readAllBytes(fileOf(url))).isEqualTo(image.bytes());
    }

    @Test
    void store_usesTheCanonicalExtensionOfEachFormat() {
        byte[] jpeg = TestImages.jpeg(50);
        byte[] webp = TestImages.webp(50);

        assertThat(storage.store(upload("x.JPEG", "image/jpeg", jpeg))).endsWith(".jpg");
        assertThat(storage.store(upload("x.jpg", "image/jpeg", jpeg))).endsWith(".jpg");
        assertThat(storage.store(upload("x.WEBP", "image/webp", webp))).endsWith(".webp");
    }

    @Test
    void store_neverUsesOrKeepsTheOriginalName() throws IOException {
        String url = storage.store(png("../../evil-name.png", 100));

        assertThat(url).doesNotContain("evil").doesNotContain("..");
        assertThat(filesInRoot()).hasSize(1);
        assertThat(filesInRoot().get(0).getFileName().toString()).doesNotContain("evil");
        assertThat(tempDir).isDirectoryNotContaining(p -> p.getFileName().toString().contains("evil"));
    }

    @Test
    void store_twoImages_getDifferentNames() throws IOException {
        String first = storage.store(png("a.png", 100));
        String second = storage.store(png("a.png", 100));

        assertThat(first).isNotEqualTo(second);
        assertThat(filesInRoot()).hasSize(2);
    }

    @Test
    void store_secondImage_neverOverwritesTheFirstFile() throws IOException {
        // The name is a random UUID, so CREATE_NEW itself cannot be provoked; this proves the observable contract.
        String first = storage.store(png("a.png", 100));
        byte[] before = Files.readAllBytes(fileOf(first));

        storage.store(png("a.png", 200));

        assertThat(Files.readAllBytes(fileOf(first))).isEqualTo(before);
    }

    @Test
    void store_keepsThePathInsideTheRoot() throws IOException {
        String url = storage.store(png("a.png", 100));

        Path file = fileOf(url).toAbsolutePath().normalize();
        assertThat(file.getParent()).isEqualTo(storage.root());
        assertThat(file).startsWith(storage.root());
    }

    @Test
    void store_withoutTransaction_keepsTheFile() throws IOException {
        String url = storage.store(png("a.png", 100));

        assertThat(fileOf(url)).exists();
    }

    @Test
    void store_inTransaction_rollbackDeletesTheNewFile() throws IOException {
        TransactionSynchronizationManager.initSynchronization();

        String url = storage.store(png("a.png", 100));
        assertThat(fileOf(url)).exists();
        completeWith(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(fileOf(url)).doesNotExist();
        assertThat(filesInRoot()).isEmpty();
    }

    @Test
    void store_inTransaction_unknownOutcomeKeepsTheNewFile() throws IOException {
        TransactionSynchronizationManager.initSynchronization();

        String url = storage.store(png("a.png", 100));
        completeWith(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(fileOf(url)).exists();
    }

    @Test
    void store_inTransaction_commitKeepsTheNewFile() {
        TransactionSynchronizationManager.initSynchronization();

        String url = storage.store(png("a.png", 100));
        completeWith(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(fileOf(url)).exists();
    }

    // ---------- deleteAfterCommit ----------

    @Test
    void deleteAfterCommit_inTransaction_deletesOnlyAfterCommit() {
        String old = storage.store(png("a.png", 100));
        TransactionSynchronizationManager.initSynchronization();

        storage.deleteAfterCommit(old);
        assertThat(fileOf(old)).exists();
        completeWith(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(fileOf(old)).doesNotExist();
    }

    @Test
    void deleteAfterCommit_inTransaction_rollbackKeepsTheOldFile() {
        String old = storage.store(png("a.png", 100));
        TransactionSynchronizationManager.initSynchronization();

        storage.deleteAfterCommit(old);
        completeWith(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(fileOf(old)).exists();
    }

    @Test
    void deleteAfterCommit_withoutTransaction_deletesRightAway() {
        String old = storage.store(png("a.png", 100));

        storage.deleteAfterCommit(old);

        assertThat(fileOf(old)).doesNotExist();
    }

    @Test
    void replaceImage_commit_deletesOldAndKeepsNew_rollbackKeepsOldAndDeletesNew() {
        String old = storage.store(png("old.png", 100));

        // rollback: the new file goes, the old one stays
        TransactionSynchronizationManager.initSynchronization();
        String rolledBack = storage.store(png("new.png", 100));
        storage.deleteAfterCommit(old);
        completeWith(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(fileOf(rolledBack)).doesNotExist();
        assertThat(fileOf(old)).exists();

        // commit: the old file goes, the new one stays
        TransactionSynchronizationManager.initSynchronization();
        String committed = storage.store(png("new.png", 100));
        storage.deleteAfterCommit(old);
        completeWith(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(fileOf(committed)).exists();
        assertThat(fileOf(old)).doesNotExist();
    }

    /** AC-006-30: seed images and odd strings are never deleted, and never fail. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "/images/products/ao-thun.jpg",
            "/uploads/../x.png",
            "/uploads/abc.png",
            "/uploads/",
            "/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.gif",
            "/uploads/3F2B8C1E-AAAA-BBBB-CCCC-0123456789AB.png",
            "/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png/../../x",
            "/uploads/sub/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png",
            "uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png",
            "../uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png",
            "",
            "   "})
    void deleteAfterCommit_ignoresAnythingThatIsNotAnUploadPath(String path) throws IOException {
        // a decoy file with the very name some of the strings point to, plus one outside the root
        Path decoy = root.resolve("3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png");
        Files.write(decoy, new byte[]{1});
        Path outside = tempDir.resolve("x.png");
        Files.write(outside, new byte[]{1});
        Path seedLike = root.resolve("ao-thun.jpg");
        Files.write(seedLike, new byte[]{1});

        storage.deleteAfterCommit(path);

        assertThat(decoy).exists();
        assertThat(outside).exists();
        assertThat(seedLike).exists();
    }

    @Test
    void deleteAfterCommit_ignoredPathInTransaction_registersNothing() {
        TransactionSynchronizationManager.initSynchronization();

        storage.deleteAfterCommit("/images/products/ao-thun.jpg");
        storage.deleteAfterCommit(null);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void deleteAfterCommit_whenFileDoesNotExist_doesNotThrow() {
        storage.deleteAfterCommit("/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png");

        TransactionSynchronizationManager.initSynchronization();
        storage.deleteAfterCommit("/uploads/3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.webp");
        completeWith(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void deleteAfterCommit_whenTheFileCannotBeDeleted_doesNotThrow() throws IOException {
        // A non-empty directory with an upload-shaped name: deleteIfExists fails with DirectoryNotEmptyException.
        String name = "3f2b8c1e-aaaa-bbbb-cccc-0123456789ab.png";
        Path dir = Files.createDirectory(root.resolve(name));
        Files.write(dir.resolve("inner"), new byte[]{1});

        storage.deleteAfterCommit("/uploads/" + name);

        assertThat(dir).exists();
    }

    @Test
    void deleteAfterCommit_doesNotTouchOtherFilesInTheRoot() {
        String keep = storage.store(png("keep.png", 100));
        String old = storage.store(png("old.png", 100));

        storage.deleteAfterCommit(old);

        assertThat(fileOf(keep)).exists();
        assertThat(fileOf(old)).doesNotExist();
    }

    // ---------- toUrl ----------

    private static final String UUID_NAME = "0b6f3c1e-9a52-4d7e-8f10-2c4a6e8b1d35";

    @Test
    void toUrl_aSeedImageThatExists_isAResourceUrlJavaFxCanLoad() {
        String url = storage.toUrl("/images/products/ao-thun-cotton-co-tron.jpg");

        assertThat(url).isNotNull();
        assertThat(url).matches("^(file|jar):.*");
        assertThat(url).endsWith("/images/products/ao-thun-cotton-co-tron.jpg");
    }

    @Test
    void toUrl_aSeedPatternPathWithoutResource_isNull() {
        assertThat(storage.toUrl("/images/products/does-not-exist.jpg")).isNull();
        assertThat(storage.toUrl("/images/products/does-not-exist.png")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "png", "webp"})
    void toUrl_anUploadedImage_isTheFileUrlInsideTheUploadRoot(String extension) {
        String name = UUID_NAME + "." + extension;

        String url = storage.toUrl("/uploads/" + name);

        assertThat(url).startsWith("file:");
        assertThat(Path.of(URI.create(url))).isEqualTo(root.resolve(name));
    }

    @Test
    void toUrl_anUploadedImageThatIsMissingOnDisk_stillReturnsItsUrl() {
        String url = storage.toUrl("/uploads/" + UUID_NAME + ".png");

        assertThat(root.resolve(UUID_NAME + ".png")).doesNotExist();
        assertThat(url).isNotNull();
    }

    @Test
    void toUrl_aStoredImage_roundTripsThroughStore() throws IOException {
        String stored = storage.store(png("a.png", 8));

        String url = storage.toUrl(stored);

        assertThat(url).isNotNull();
        assertThat(Path.of(URI.create(url))).isEqualTo(fileOf(stored)).exists();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "http://example.com/x.jpg",
            "https://example.com/uploads/0b6f3c1e-9a52-4d7e-8f10-2c4a6e8b1d35.jpg",
            "file:/etc/passwd",
            "/uploads/../x.jpg",
            "/uploads/abc.jpg",
            "/uploads/0b6f3c1e-9a52-4d7e-8f10-2c4a6e8b1d35.gif",
            "/uploads/0B6F3C1E-9A52-4D7E-8F10-2C4A6E8B1D35.jpg",
            "/uploads/0b6f3c1e-9a52-4d7e-8f10-2c4a6e8b1d35.jpg\n",
            "/uploads/0b6f3c1e-9a52-4d7e-8f10-2c4a6e8b1d35.jpg/../../x.jpg",
            "C:\\x.jpg",
            "/images/products/../../application.yml",
            "/images/products/x.webp",
            "/images/products/ao-thun-cotton-co-tron.jpg\n",
            "/images/products/AO-THUN.jpg",
            "/images/products/sub/x.jpg",
            "ao-thun.jpg"
    })
    void toUrl_whateverIsNotASeedImageOrAnUpload_isNull(String imagePath) {
        assertThat(storage.toUrl(imagePath)).isNull();
    }

    @Test
    void toUrl_whenIgnoringAValue_logsOneDebugLineWithoutTheValue() {
        String secret = "http://example.com/secret-token-123.jpg";

        try (LogCapture log = LogCapture.of(LoggerFactory.getLogger(LocalStorageService.class))) {
            assertThat(storage.toUrl(secret)).isNull();

            List<ILoggingEvent> debug = log.events(Level.DEBUG);
            assertThat(debug).hasSize(1);
            assertThat(debug.get(0).getFormattedMessage()).doesNotContain(secret).doesNotContain("secret-token");
            assertThat(log.events()).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("secret-token"));
        }
    }
}
