package com.labdms.domain.model.versioning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Content-addressable snapshot of LaTeX source.
 * Uses SHA-256 hashing for deduplication: identical content produces the same hash,
 * so only one copy is stored.
 */
public class ContentSnapshot {

    private UUID id;
    private String latexSource;
    private String contentHash;
    private long sizeBytes;
    private Instant createdAt;

    public ContentSnapshot() {
    }

    public ContentSnapshot(UUID id, String latexSource, String contentHash, long sizeBytes, Instant createdAt) {
        this.id = id;
        this.latexSource = latexSource;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    /**
     * Factory method that computes the SHA-256 hash and byte size from the LaTeX source.
     */
    public static ContentSnapshot create(String latexSource) {
        if (latexSource == null || latexSource.isBlank()) {
            throw new IllegalArgumentException("LaTeX source must not be blank");
        }
        ContentSnapshot snapshot = new ContentSnapshot();
        snapshot.id = UUID.randomUUID();
        snapshot.latexSource = latexSource;
        snapshot.contentHash = computeSha256(latexSource);
        snapshot.sizeBytes = latexSource.getBytes(StandardCharsets.UTF_8).length;
        snapshot.createdAt = Instant.now();
        return snapshot;
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getLatexSource() {
        return latexSource;
    }

    public void setLatexSource(String latexSource) {
        this.latexSource = latexSource;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
