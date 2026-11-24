package com.macstudio.backend.member.scheduler;

import com.macstudio.backend.member.service.WithdrawnMemberPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 명시적으로 활성화된 환경에서만 매일 탈퇴 회원 파기 배치를 실행한다. */
@Component
@ConditionalOnProperty(name = "app.member-purge.enabled", havingValue = "true")
public class WithdrawnMemberPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(WithdrawnMemberPurgeScheduler.class);
    private final WithdrawnMemberPurgeService purgeService;
    private final int batchSize;

    public WithdrawnMemberPurgeScheduler(WithdrawnMemberPurgeService purgeService,
            @Value("${app.member-purge.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("app.member-purge.batch-size must be between 1 and 1000");
        }
        this.purgeService = purgeService;
        this.batchSize = batchSize;
    }

    /** UTC 기준 cron으로 실행하고 민감정보 없이 처리 건수만 기록한다. */
    @Scheduled(cron = "${app.member-purge.cron:0 0 3 * * *}", zone = "UTC")
    public void purge() {
        var result = purgeService.purgeDueMembers(batchSize);
        if (result.attempted() > 0) {
            log.info("Withdrawn member purge completed: attempted={}, purged={}, skipped={}, failed={}",
                    result.attempted(), result.purged(), result.skipped(), result.failed());
        }
    }
}
