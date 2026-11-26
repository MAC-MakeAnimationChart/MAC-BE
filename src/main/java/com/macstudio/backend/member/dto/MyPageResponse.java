package com.macstudio.backend.member.dto;

import java.time.Instant;
import java.util.List;

/** 마이페이지 상단 요약과 검색된 데이터셋 폴더 한 페이지를 반환한다. */
public record MyPageResponse(
        String email,
        Summary summary,
        List<DatasetFolder> datasets,
        PageInfo page
) {
    /** 로그인 회원의 전체 데이터셋·차트·활성 저장량을 나타낸다. */
    public record Summary(long datasetCount, long chartCount, long storageUsedBytes,
            long storageLimitBytes) {}

    /** 데이터셋 폴더 목록에 필요한 기본 정보와 차트 개수만 제공한다. */
    public record DatasetFolder(String datasetId, String name, String revision,
            int rowCount, int columnCount, long chartCount, Instant updatedAt) {}

    /** 0부터 시작하는 데이터셋 페이지 위치와 다음 페이지 존재 여부를 제공한다. */
    public record PageInfo(int number, int size, long totalElements, int totalPages,
            boolean hasNext) {}
}
