package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.dto.ChartThumbnailSaveCommand;
import com.macstudio.backend.chart.dto.ChartThumbnailSaveResponse;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import com.macstudio.backend.storage.service.ChartThumbnailStorage.StoredThumbnail;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 비동기 렌더링 썸네일의 양쪽 revision을 재검증하고 S3 참조 교체를 조정한다. */
@Service
public class ChartThumbnailService {
    private final ChartRepository charts;
    private final DatasetRepository datasets;
    private final ChartThumbnailStorage thumbnails;
    private final TransactionTemplate transaction;
    private final long maxStorageBytes;

    public ChartThumbnailService(ChartRepository charts, DatasetRepository datasets,
            ChartThumbnailStorage thumbnails, PlatformTransactionManager manager,
            @Value("${app.limits.member-storage-bytes}") long maxStorageBytes) {
        this.charts = charts;
        this.datasets = datasets;
        this.thumbnails = thumbnails;
        this.transaction = new TransactionTemplate(manager);
        this.maxStorageBytes = maxStorageBytes;
    }

    /** S3 업로드 전후에 revision을 확인하고 실패 시 새 객체를 미참조 상태로 남기지 않는다. */
    public ChartThumbnailSaveResponse save(String subject, String chartIdValue,
            ChartThumbnailSaveCommand command, MultipartFile thumbnail) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long chartId = parsePositiveLong(chartIdValue, HttpStatus.BAD_REQUEST, "INVALID_CHART_ID");
        long datasetRevision = ChartUpdateValidator.positiveLong(
                command.basedOnDatasetRevision(), "INVALID_DATASET_REVISION");
        long chartRevision = ChartUpdateValidator.positiveLong(
                command.basedOnChartRevision(), "INVALID_CHART_REVISION");

        Chart observed = charts.findOwnedById(chartId, memberId).orElseThrow(this::notFound);
        validateMemberAndRevisions(observed, datasetRevision, chartRevision);

        StoredThumbnail uploaded = thumbnails.upload(chartId, thumbnail);
        try {
            ThumbnailOutcome outcome = transaction.execute(status -> attachInTransaction(
                    memberId, chartId, datasetRevision, chartRevision, uploaded));
            if (outcome == null) throw new IllegalStateException("Thumbnail transaction returned no result");
            if (outcome.previousThumbnailKey() != null) {
                thumbnails.deleteBestEffort(outcome.previousThumbnailKey());
            }
            return outcome.response();
        } catch (RuntimeException failure) {
            thumbnails.deleteBestEffort(uploaded.key());
            throw failure;
        }
    }

    /** DB 잠금은 S3 업로드 뒤에만 획득하고 결합 직전에 revision과 저장량을 다시 확인한다. */
    private ThumbnailOutcome attachInTransaction(long memberId, long chartId,
            long datasetRevision, long chartRevision, StoredThumbnail uploaded) {
        Chart chart = charts.findOwnedByIdForUpdate(chartId, memberId).orElseThrow(this::notFound);
        validateMemberAndRevisions(chart, datasetRevision, chartRevision);

        long currentStorage = datasets.calculateStorageBytes(memberId);
        long oldThumbnailBytes = chart.getThumbnailSizeBytes() == null ? 0L : chart.getThumbnailSizeBytes();
        long finalStorage = currentStorage - oldThumbnailBytes + uploaded.sizeBytes();
        if (finalStorage > maxStorageBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_LIMIT_EXCEEDED",
                    "회원 저장 용량을 초과했습니다.");
        }

        String previousKey = chart.getThumbnailKey();
        chart.attachThumbnail(uploaded.key(), uploaded.sizeBytes(), uploaded.contentType(), datasetRevision);
        chart.getDataset().touch();
        charts.flush();

        ChartThumbnailSaveResponse response = new ChartThumbnailSaveResponse(
                chart.getId().toString(), chart.getDataset().getId().toString(),
                Long.toString(chart.getRevision()), Long.toString(chart.getDataset().getRevision()),
                "READY", chart.getUpdatedAt().toInstant(ZoneOffset.UTC));
        String obsoleteKey = previousKey != null && !previousKey.equals(uploaded.key()) ? previousKey : null;
        return new ThumbnailOutcome(response, obsoleteKey);
    }

    private void validateMemberAndRevisions(Chart chart, long datasetRevision, long chartRevision) {
        if (!chart.getDataset().getMember().isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "현재 회원을 인증할 수 없습니다.");
        }
        if (chart.getRevision() != chartRevision
                || chart.getDataset().getRevision() != datasetRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "THUMBNAIL_REVISION_CONFLICT",
                    "데이터 또는 차트가 변경되었습니다. 최신 상태로 다시 렌더링해 주세요.");
        }
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
        return new ApiException(HttpStatus.NOT_FOUND, "CHART_NOT_FOUND", "차트를 찾을 수 없습니다.");
    }

    private record ThumbnailOutcome(ChartThumbnailSaveResponse response, String previousThumbnailKey) {}
}
