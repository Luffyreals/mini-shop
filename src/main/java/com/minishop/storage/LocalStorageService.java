package com.minishop.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bản lưu ảnh ra thư mục local. Tên file là UUID (tên gốc không bao giờ được dùng hay log), ghi bằng
 * {@code CREATE_NEW}, và đường dẫn đã chuẩn hóa phải nằm trực tiếp trong thư mục gốc.
 */
@Service
class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);
    private static final String URL_PREFIX = "/uploads/";
    private static final Pattern STORED_PATH = Pattern.compile(
            "^/uploads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private final Path root;

    LocalStorageService(@Value("${app.storage.local.upload-dir}") String uploadDir) {
        this.root = UploadRoot.resolve(uploadDir);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create upload directory " + root, e);
        }
    }

    Path root() {
        return root;
    }

    @Override
    public String store(ImageUpload image) {
        String name = UUID.randomUUID() + "." + image.format().extension();
        Path target = root.resolve(name).normalize();
        if (!root.equals(target.getParent())) {
            throw new IllegalStateException("Resolved path escapes the upload directory");
        }
        try {
            Files.write(target, image.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store image " + name, e);
        }
        log.info("Stored image {} ({} bytes)", name, image.size());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // Only on a certain rollback: on STATUS_UNKNOWN the commit may have succeeded, and deleting
                    // the file would leave a row pointing at nothing (an orphan file is the lesser evil).
                    if (status == STATUS_ROLLED_BACK) {
                        deleteQuietly(target);
                    }
                }
            });
        }
        return URL_PREFIX + name;
    }

    @Override
    public void deleteAfterCommit(String imagePath) {
        if (imagePath == null || !STORED_PATH.matcher(imagePath).matches()) {
            log.debug("Not deleting an image path that is not an upload");
            return;
        }
        Path target = root.resolve(imagePath.substring(URL_PREFIX.length())).normalize();
        if (!root.equals(target.getParent())) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(target);
                }
            });
        } else {
            deleteQuietly(target);
        }
    }

    @Override
    public String toUrl(String imagePath) {
        return imagePath == null || imagePath.isBlank() ? null : imagePath;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete image {}: {}", file.getFileName(), e.toString());
        }
    }
}
