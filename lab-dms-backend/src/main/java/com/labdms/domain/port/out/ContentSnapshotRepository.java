package com.labdms.domain.port.out;

import com.labdms.domain.model.versioning.ContentSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port for content snapshot persistence.
 * Supports content-addressable storage via SHA-256 hash lookup.
 */
public interface ContentSnapshotRepository {

    ContentSnapshot save(ContentSnapshot snapshot);

    Optional<ContentSnapshot> findById(UUID id);

    /**
     * Finds existing snapshot by content hash for deduplication.
     */
    Optional<ContentSnapshot> findByContentHash(String contentHash);
}
