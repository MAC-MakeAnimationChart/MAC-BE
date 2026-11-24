package com.macstudio.backend.storage.scheduler;

import com.macstudio.backend.storage.service.OrphanThumbnailCleanupService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 명시적으로 활성화한 환경에서만 미참조 썸네일을 안전 유예 후 정리한다. */
@Component
@ConditionalOnProperty(name = {"app.storage.enabled", "app.storage.orphan-cleanup.enabled"},
        havingValue = "true")
public class OrphanThumbnailCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrphanThumbnailCleanupScheduler.class);
    private final OrphanThumbnailCleanupService service;
    private final int scanLimit;
    private final int deleteLimit;
    private final Duration grace;

    public OrphanThumbnailCleanupScheduler(OrphanThumbnailCleanupService service,
            @Value("${app.storage.orphan-cleanup.scan-limit:5000}") int scanLimit,
            @Value("${app.storage.orphan-cleanup.delete-limit:100}") int deleteLimit,
            @Value("${app.storage.orphan-cleanup.grace-hours:24}") long graceHours) {
        if (graceHours < 1) throw new IllegalArgumentException("Cleanup grace must be positive");
        this.service = service;
        this.scanLimit = scanLimit;
        this.deleteLimit = deleteLimit;
        this.grace = Duration.ofHours(graceHours);
    }

    @Scheduled(cron = "${app.storage.orphan-cleanup.cron:0 30 3 * * *}", zone = "UTC")
    public void cleanup() {
        var result = service.cleanup(scanLimit, deleteLimit, grace);
        if (result.deleted() > 0 || result.failed() > 0) {
            log.info("Orphan thumbnail cleanup completed: scanned={}, deleted={}, failed={}",
                    result.scanned(), result.deleted(), result.failed());
        }
    }
}
