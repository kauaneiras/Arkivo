package com.labdms.infrastructure.config;

import com.labdms.infrastructure.adapter.out.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims blobs that no version references any more.
 *
 * <p>Deleting a file, replacing a tree with a ZIP, discarding a draft and an
 * import that fails half-way all leave blobs behind. Without a collector the
 * store only ever grows. It runs nightly with a grace period, so a blob that
 * an import wrote a moment ago is never mistaken for garbage.
 */
@Component
@Profile("!test")
public class BlobGarbageCollector {

    private static final Logger logger = LoggerFactory.getLogger(BlobGarbageCollector.class);

    private final BlobStore blobStore;
    private final int graceHours;

    public BlobGarbageCollector(BlobStore blobStore,
                                @Value("${labdms.storage.gc-grace-hours:24}") int graceHours) {
        this.blobStore = blobStore;
        this.graceHours = graceHours;
    }

    @Scheduled(cron = "${labdms.storage.gc-cron:0 30 3 * * *}",
               zone = "${labdms.review-reminder.zone:America/Sao_Paulo}")
    public void scheduledRun() {
        try {
            int removed = runNow();
            logger.info("Blob garbage collection finished: {} blob(s) removed", removed);
        } catch (Exception e) {
            logger.error("Blob garbage collection failed", e);
        }
    }

    /** Same routine, callable from the storage screen. */
    public int runNow() {
        return blobStore.collectGarbage(graceHours);
    }

    public int getGraceHours() {
        return graceHours;
    }
}
