package com.macstudio.backend.member.service;

import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.dto.MyPageResponse;
import com.macstudio.backend.member.dto.MyPageResponse.DatasetFolder;
import com.macstudio.backend.member.dto.MyPageResponse.PageInfo;
import com.macstudio.backend.member.dto.MyPageResponse.Summary;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 마이페이지 상단 요약과 검색된 데이터셋 폴더 페이지를 읽기 전용으로 조립한다. */
@Service
public class MyPageQueryService {
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SIZE = 50;
    private final MemberRepository members;
    private final DatasetRepository datasets;
    private final ChartRepository charts;
    private final long storageLimitBytes;

    public MyPageQueryService(MemberRepository members, DatasetRepository datasets,
            ChartRepository charts,
            @Value("${app.limits.member-storage-bytes}") long storageLimitBytes) {
        this.members = members;
        this.datasets = datasets;
        this.charts = charts;
        this.storageLimitBytes = storageLimitBytes;
    }

    /** 데이터셋 한 페이지를 조회하고 폴더별 차트 개수만 묶음 집계해 반환한다. */
    @Transactional(readOnly = true)
    public MyPageResponse get(String subject, int page, int size, String queryValue) {
        long memberId = parseMemberId(subject);
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page와 size 범위를 확인해 주세요.");
        }
        String query = queryValue == null ? "" : queryValue.strip();
        if (query.codePointCount(0, query.length()) > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATASET_QUERY",
                    "데이터셋 검색어가 너무 깁니다.");
        }

        Member member = members.findById(memberId).filter(Member::isActive).orElseThrow(this::unauthorized);
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var datasetPage = datasets.findMyPageByMemberId(memberId, query, pageable);
        List<Long> datasetIds = datasetPage.getContent().stream().map(Dataset::getId).toList();
        Map<Long, Long> chartCounts = new HashMap<>();
        if (!datasetIds.isEmpty()) {
            for (ChartRepository.DatasetChartCount count : charts.countByDatasetIds(datasetIds)) {
                chartCounts.put(count.getDatasetId(), count.getChartCount());
            }
        }

        List<DatasetFolder> folders = datasetPage.getContent().stream()
                .map(dataset -> folder(dataset, chartCounts.getOrDefault(dataset.getId(), 0L)))
                .toList();
        Summary summary = new Summary(datasets.countByMember_Id(memberId),
                charts.countByDataset_Member_Id(memberId), datasets.calculateStorageBytes(memberId),
                storageLimitBytes);
        PageInfo pageInfo = new PageInfo(datasetPage.getNumber(), datasetPage.getSize(),
                datasetPage.getTotalElements(), datasetPage.getTotalPages(), datasetPage.hasNext());
        return new MyPageResponse(member.getEmail(), summary, folders, pageInfo);
    }

    private DatasetFolder folder(Dataset dataset, long chartCount) {
        return new DatasetFolder(dataset.getId().toString(), dataset.getName(),
                Long.toString(dataset.getRevision()), dataset.getRowCount(), dataset.getColumnCount(),
                chartCount, dataset.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private long parseMemberId(String subject) {
        try {
            if (subject == null || !subject.equals(subject.strip())) throw new NumberFormatException();
            long parsed = Long.parseLong(subject);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw unauthorized();
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다.");
    }
}
