package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.chart.dto.ChartPatchResponse;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartUpdateValidator.PreparedChartPatch;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import com.macstudio.backend.storage.service.ChartThumbnailStorage.StoredThumbnail;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 차트 부분 수정, revision 충돌, 저장량과 S3 썸네일 교체를 조정한다. */
@Service
public class ChartUpdateService {
    private final ChartRepository charts;
    private final DatasetRepository datasets;
    private final ChartUpdateValidator validator;
    private final ChartThumbnailStorage thumbnails;
    private final TransactionTemplate transaction;
    private final long maxStorageBytes;

    public ChartUpdateService(ChartRepository charts, DatasetRepository datasets,
            ChartUpdateValidator validator, ChartThumbnailStorage thumbnails,
            PlatformTransactionManager manager,
            @Value("${app.limits.member-storage-bytes}") long maxStorageBytes) {
        this.charts = charts;
        this.datasets = datasets;
        this.validator = validator;
        this.thumbnails = thumbnails;
        this.transaction = new TransactionTemplate(manager);
        this.maxStorageBytes = maxStorageBytes;
    }

    public ChartPatchResponse patch(String subject, String chartIdValue,
            ChartPatchCommand command, MultipartFile thumbnail) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long chartId = parsePositiveLong(chartIdValue, HttpStatus.BAD_REQUEST, "INVALID_CHART_ID");
        PreparedChartPatch prepared = validator.validate(command);

        Chart observed = charts.findOwnedById(chartId, memberId).orElseThrow(this::notFound);
        if (!observed.getDataset().getMember().isActive()) throw unauthorized();
        if (observed.getRevision() != prepared.expectedRevision()) throw revisionConflict();

        StoredThumbnail uploaded = null;
        boolean thumbnailFailed = false;
        try {
            if (thumbnail != null && !thumbnail.isEmpty()) {
                try {
                    uploaded = thumbnails.upload(chartId, thumbnail);
                } catch (ApiException failure) {
                    // 차트 설정 저장을 우선하며 FE는 FAILED 상태에서 썸네일만 재시도할 수 있다.
                    thumbnailFailed = true;
                }
            }
            StoredThumbnail finalUploaded = uploaded;
            boolean finalThumbnailFailed = thumbnailFailed;
            UpdateOutcome outcome = transaction.execute(status ->
                    updateInTransaction(memberId, chartId, prepared, finalUploaded, finalThumbnailFailed));
            if (outcome == null) throw new IllegalStateException("Chart update transaction returned no result");
            if (outcome.previousThumbnailKey() != null) {
                thumbnails.deleteBestEffort(outcome.previousThumbnailKey());
            }
            return outcome.response();
        } catch (RuntimeException failure) {
            if (uploaded != null) thumbnails.deleteBestEffort(uploaded.key());
            throw failure;
        }
    }

    private UpdateOutcome updateInTransaction(long memberId, long chartId,
            PreparedChartPatch prepared, StoredThumbnail uploaded, boolean thumbnailFailed) {
        Chart chart = charts.findOwnedByIdForUpdate(chartId, memberId).orElseThrow(this::notFound);
        if (!chart.getDataset().getMember().isActive()) throw unauthorized();
        if (chart.getRevision() != prepared.expectedRevision()) throw revisionConflict();

        String finalTitle = prepared.title() == null ? chart.getTitle() : prepared.title();
        String finalType = prepared.chartType() == null ? chart.getChartType() : prepared.chartType();
        int finalSchema = prepared.figureSchemaVersion() == null
                ? chart.getFigureSchemaVersion() : prepared.figureSchemaVersion();
        String finalFigure = prepared.figureJson() == null ? chart.getFigureJson() : prepared.figureJson();
        boolean settingsChanged = !finalTitle.equals(chart.getTitle())
                || !finalType.equals(chart.getChartType())
                || finalSchema != chart.getFigureSchemaVersion()
                || !finalFigure.equals(chart.getFigureJson());

        long currentStorage = datasets.calculateStorageBytes(memberId);
        long oldFigureBytes = chart.getFigureJson().getBytes(StandardCharsets.UTF_8).length;
        long oldThumbnailBytes = chart.getThumbnailSizeBytes() == null ? 0L : chart.getThumbnailSizeBytes();
        long finalFigureBytes = finalFigure.getBytes(StandardCharsets.UTF_8).length;
        long finalThumbnailBytes = uploaded != null ? uploaded.sizeBytes()
                : settingsChanged ? 0L : oldThumbnailBytes;
        long finalStorage = currentStorage - oldFigureBytes - oldThumbnailBytes
                + finalFigureBytes + finalThumbnailBytes;
        if (finalStorage > maxStorageBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_LIMIT_EXCEEDED",
                    "회원 저장 용량을 초과했습니다.");
        }

        String previousKey = chart.getThumbnailKey();
        if (settingsChanged) chart.updateSettings(finalTitle, finalType, finalSchema, finalFigure);
        if (uploaded != null) chart.attachThumbnail(uploaded.key(), uploaded.sizeBytes(), uploaded.contentType());
        if (settingsChanged || uploaded != null) chart.getDataset().touch();
        charts.flush();

        String state = thumbnailFailed ? "FAILED"
                : chart.getThumbnailKey() == null ? "NONE" : "READY";
        ChartPatchResponse response = new ChartPatchResponse(chart.getId().toString(),
                chart.getDataset().getId().toString(), Long.toString(chart.getRevision()),
                Long.toString(chart.getDataset().getRevision()), state,
                chart.getUpdatedAt().toInstant(ZoneOffset.UTC));
        String obsoleteKey = previousKey != null && !previousKey.equals(chart.getThumbnailKey())
                ? previousKey : null;
        return new UpdateOutcome(response, obsoleteKey);
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

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다.");
    }

    private ApiException revisionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "CHART_REVISION_CONFLICT",
                "다른 저장 내용이 먼저 반영되었습니다. 최신 차트를 다시 불러와 주세요.");
    }

    private record UpdateOutcome(ChartPatchResponse response, String previousThumbnailKey) {}
}
