package com.labdms.infrastructure.adapter.out.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores document attachments (external files: scanned calibration certificates,
 * photos, spreadsheets) on local disk, under a persistent volume.
 *
 * Files are addressed by an opaque UUID-prefixed name on disk; the human-readable
 * original filename is kept only in the database, never trusted for path
 * construction (prevents path traversal / overwrite attacks from a crafted
 * filename such as "../../etc/passwd").
 */
@Component
public class LocalAttachmentStorage {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Path baseDir;

    public LocalAttachmentStorage(@Value("${attachments.storage-dir:/app/attachments}") String storageDir) {
        this.baseDir = Path.of(storageDir);
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create attachments directory: " + storageDir, e);
        }
    }

    /**
     * Saves the file under {baseDir}/{documentId}/{uuid}_{sanitizedName} and
     * returns the relative storage path to persist in the database.
     */
    public String save(UUID documentId, MultipartFile file) {
        try {
            Path documentDir = baseDir.resolve(documentId.toString()).normalize();
            if (!documentDir.startsWith(baseDir)) {
                throw new IllegalArgumentException("Invalid document id");
            }
            Files.createDirectories(documentDir);

            String safeName = sanitizeFileName(file.getOriginalFilename());
            String storedName = UUID.randomUUID() + "_" + safeName;
            Path target = documentDir.resolve(storedName).normalize();
            if (!target.startsWith(documentDir)) {
                throw new IllegalArgumentException("Invalid file name");
            }

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return documentId + "/" + storedName;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store attachment", e);
        }
    }

    public Path resolve(String relativePath) {
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid attachment path");
        }
        return resolved;
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // Best-effort: the DB row is the source of truth; an orphaned file on
            // disk is a cheaper failure mode than blocking the delete operation.
        }
    }

    private String sanitizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "arquivo";
        }
        // Strip any directory component a malicious client might include.
        String name = Path.of(originalName).getFileName().toString();
        String sanitized = UNSAFE_CHARS.matcher(name).replaceAll("_");
        return sanitized.isBlank() ? "arquivo" : sanitized;
    }
}
