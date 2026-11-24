package com.macstudio.backend.member.service;

import com.macstudio.backend.auth.repository.AuthChallengeRepository;
import com.macstudio.backend.auth.repository.OAuthTransactionRepository;
import com.macstudio.backend.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 3개월 보관 기한이 지난 탈퇴 회원과 연결 개인정보를 짧은 DB 트랜잭션으로 파기한다. */
@Service
public class WithdrawnMemberPurgeService {
    private static final Logger log = LoggerFactory.getLogger(WithdrawnMemberPurgeService.class);
    private final MemberRepository members;
    private final AuthChallengeRepository challenges;
    private final OAuthTransactionRepository oauthTransactions;
    private final TransactionTemplate transaction;

    public WithdrawnMemberPurgeService(MemberRepository members,
            AuthChallengeRepository challenges,
            OAuthTransactionRepository oauthTransactions,
            PlatformTransactionManager manager) {
        this.members = members;
        this.challenges = challenges;
        this.oauthTransactions = oauthTransactions;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 기한이 지난 회원을 오래된 순서로 조회하고 회원별 독립 트랜잭션으로 파기한다. */
    public PurgeResult purgeDueMembers(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        var dueIds = members.findPurgeDueIds(batchSize);
        int purged = 0;
        int skipped = 0;
        int failed = 0;
        for (Long memberId : dueIds) {
            try {
                Boolean deleted = transaction.execute(status -> {
                    var member = members.findPurgeDueByIdForUpdate(memberId).orElse(null);
                    if (member == null) return false;

                    oauthTransactions.deleteUnboundByMemberIdentity(memberId);
                    String email = member.getEmail();
                    if (email != null) {
                        challenges.deleteUnboundByTargetEmail(email);
                        oauthTransactions.deleteUnboundByVerifiedEmail(email);
                    }
                    // 연결 인증·동의·데이터셋·차트는 검증된 DB FK CASCADE로 함께 파기한다.
                    members.delete(member);
                    members.flush();
                    return true;
                });
                if (Boolean.TRUE.equals(deleted)) purged++;
                else skipped++;
            } catch (RuntimeException failure) {
                // 한 회원의 실패가 다음 회원의 법정 파기 처리를 막지 않게 한다.
                log.error("Withdrawn member purge transaction failed without exposing member data", failure);
                failed++;
            }
        }
        return new PurgeResult(dueIds.size(), purged, skipped, failed);
    }

    /** 개인정보나 회원 ID를 로그에 남기지 않고 집계만 전달한다. */
    public record PurgeResult(int attempted, int purged, int skipped, int failed) {}
}
