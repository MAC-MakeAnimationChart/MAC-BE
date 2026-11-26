package com.macstudio.backend.dataset.service;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse.ChartItem;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse.DatasetInfo;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse.PageInfo;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 선택 데이터셋의 기본 정보와 차트 카드 페이지를 소유권 범위에서 조회한다. */
@Service
public class DatasetChartQueryService {
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SIZE = 50;
    private final DatasetRepository datasets;
    private final ChartRepository charts;

    public DatasetChartQueryService(DatasetRepository datasets, ChartRepository charts) {
        this.datasets = datasets;
        this.charts = charts;
    }

    /** 데이터셋 선택과 차트 페이지 이동에 사용하는 독립 조회 결과를 조립한다. */
    @Transactional(readOnly = true)
    public DatasetChartPageResponse get(String subject, String datasetIdValue, int page, int size) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long datasetId = parsePositiveLong(datasetIdValue, HttpStatus.BAD_REQUEST, "INVALID_DATASET_ID");
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page와 size 범위를 확인해 주세요.");
        }

        Dataset dataset = datasets.findOwnedById(datasetId, memberId).orElseThrow(this::notFound);
        if (!dataset.getMember().isActive()) throw unauthorized();
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var chartPage = charts.findByDataset_Id(datasetId, pageable);
        List<ChartItem> items = chartPage.getContent().stream()
                .map(chart -> chartItem(chart, dataset.getRevision()))
                .toList();
        long chartCount = chartPage.getTotalElements();
        DatasetInfo info = new DatasetInfo(dataset.getId().toString(), dataset.getName(),
                Long.toString(dataset.getRevision()), dataset.getRowCount(), dataset.getColumnCount(),
                chartCount, dataset.getUpdatedAt().toInstant(ZoneOffset.UTC));
        PageInfo pageInfo = new PageInfo(chartPage.getNumber(), chartPage.getSize(), chartCount,
                chartPage.getTotalPages(), chartPage.hasNext());
        return new DatasetChartPageResponse(info, items, pageInfo);
    }

    private ChartItem chartItem(Chart chart, long datasetRevision) {
        boolean ready = chart.getThumbnailKey() != null
                && Objects.equals(chart.getThumbnailChartRevision(), chart.getRevision())
                && Objects.equals(chart.getThumbnailDatasetRevision(), datasetRevision);
        return new ChartItem(chart.getId().toString(), chart.getTitle(), chart.getChartType(),
                Long.toString(chart.getRevision()), ready ? "READY" : "NONE",
                ready ? "/api/v1/charts/" + chart.getId() + "/thumbnail" : null,
                chart.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private static long parsePositiveLong(String value, HttpStatus status, String code) {
        try {
            if (value == null || !value.equals(value.strip())) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ApiException(status, code, "식별자 또는 인증 정보를 확인해 주세요.");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DATASET_NOT_FOUND", "데이터셋을 찾을 수 없습니다.");
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다.");
    }
}
