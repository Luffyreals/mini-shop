package com.minishop.storage;

import com.minishop.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
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

    @Test
    void toUrl_nullOrBlank_isNull() {
        assertThat(storage.toUrl(null)).isNull();
        assertThat(storage.toUrl("")).isNull();
        assertThat(storage.toUrl("   ")).isNull();
    }

    @Test
    void toUrl_aPath_isThePathItself() {
        assertThat(storage.toUrl("/uploads/x.png")).isEqualTo("/uploads/x.png");
        assertThat(storage.toUrl("/images/products/ao-thun.jpg")).isEqualTo("/images/products/ao-thun.jpg");
    }
}
