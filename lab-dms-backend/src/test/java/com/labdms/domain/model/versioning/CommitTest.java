package com.labdms.domain.model.versioning;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the production incident where creating a second document
 * from the same blank template raised:
 *
 *   duplicate key value violates unique constraint "uk7bwlrf4a6alr5ghaar2iglbs"
 *   Detail: Key (sha256_hash)=(...) already exists.
 *
 * Root cause: Commit.create() used to store the CONTENT hash directly as the
 * commit's sha256Hash, but commits.sha256_hash is UNIQUE and must identify the
 * COMMIT (git semantics), not the content — content already has its own
 * dedup key on content_snapshots.content_hash.
 */
class CommitTest {

    // The exact content hash from the production stack trace that triggered this fix.
    private static final String SAME_CONTENT_HASH =
            "ae220287ae9848d3ef7c158e5e69393367a43d459b150a12996b54216aa3421e";

    @Test
    void twoCommitsFromIdenticalTemplateContentGetDifferentHashes() {
        // Reproduces the exact failure: two different documents, both created
        // from the same blank template, so their content hash is identical.
        Commit commitForDocA = Commit.create(
                UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), SAME_CONTENT_HASH, "Initial document creation", UUID.randomUUID());
        Commit commitForDocB = Commit.create(
                UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), SAME_CONTENT_HASH, "Initial document creation", UUID.randomUUID());

        assertThat(commitForDocA.getSha256Hash()).isNotEqualTo(commitForDocB.getSha256Hash());
    }

    @Test
    void restoringAVersionProducesADifferentHashThanTheOriginalCommit() {
        UUID documentId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();

        Commit original = Commit.create(documentId, branchId, null, snapshotId,
                SAME_CONTENT_HASH, "Initial document creation", authorId);

        // "Restore" creates a NEW commit with the OLD content but a new message/parent.
        Commit restored = Commit.create(documentId, branchId, original.getId(), snapshotId,
                SAME_CONTENT_HASH, "Restore version v1", authorId);

        assertThat(restored.getSha256Hash()).isNotEqualTo(original.getSha256Hash());
    }

    @Test
    void hashIsAValid64CharacterHexStringCompatibleWithTheVarchar64Column() {
        Commit commit = Commit.create(UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), SAME_CONTENT_HASH, "msg", UUID.randomUUID());

        assertThat(commit.getSha256Hash())
                .hasSize(64)
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void manyCommitsWithIdenticalContentAllProduceUniqueHashes() {
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Commit commit = Commit.create(UUID.randomUUID(), UUID.randomUUID(), null,
                    UUID.randomUUID(), SAME_CONTENT_HASH, "Initial document creation", UUID.randomUUID());
            hashes.add(commit.getSha256Hash());
        }
        assertThat(hashes).hasSize(500);
    }
}
