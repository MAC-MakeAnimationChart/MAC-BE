package com.macstudio.backend.dataset.service;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetPatchCommand;
import com.macstudio.backend.dataset.dto.DatasetPatchResponse;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetUpdateValidator.PreparedDatasetPatch;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 데이터셋 부분 저장과 데이터 변경에 따른 모든 하위 썸네일 무효화를 조정한다. */
@Service
public class DatasetUpdateService {
    private final DatasetRepository datasets;
    private final ChartRepository charts;
    private final DatasetUpdateValidator validator;
    private final ChartThumbnailStorage thumbnails;
    private final TransactionTemplate transaction;
    private final long maxDatasetBytes;
    private final long maxStorageBytes;

    public DatasetUpdateService(DatasetRepository datasets, ChartRepository charts,
            DatasetUpdateValidator validator, ChartThumbnailStorage thumbnails,
            PlatformTransactionManager manager,
            @Value("${app.limits.member-upload-bytes}") long maxDatasetBytes,
            @Value("${app.limits.member-storage-bytes}") long maxStorageBytes) {
        this.datasets = datasets;
        this.charts = charts;
        this.validator = validator;
        this.thumbnails = thumbnails;
        this.transaction = new TransactionTemplate(manager);
        this.maxDatasetBytes = maxDatasetBytes;
        this.maxStorageBytes = maxStorageBytes;
    }

    public DatasetPatchResponse patch(String subject, String datasetIdValue, DatasetPatchCommand command) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long datasetId = parsePositiveLong(datasetIdValue, HttpStatus.BAD_REQUEST, "INVALID_DATASET_ID");
        PreparedDatasetPatch prepared = validator.validate(command, maxDatasetBytes);
        UpdateOutcome outcome;
        try {
            outcome = transaction.execute(status -> updateInTransaction(memberId, datasetId, prepared));
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "DATASET_NAME_ALREADY_USED",
                    "같은 이름의 데이터셋이 이미 있습니다.");
        }
        if (outcome == null) throw new IllegalStateException("Dataset update transaction returned no result");
        for (String key : outcome.obsoleteThumbnailKeys()) thumbnails.deleteBestEffort(key);
        return outcome.response();
    }

    private UpdateOutcome updateInTransaction(long memberId, long datasetId, PreparedDatasetPatch prepared) {
        Dataset dataset = datasets.findOwnedByIdForUpdate(datasetId, memberId).orElseThrow(this::notFound);
        if (!dataset.getMember().isActive()) throw unauthorized();
        if (dataset.getRevision() != prepared.expectedRevision()) throw revisionConflict();

        String finalName = prepared.name() == null ? dataset.getName() : prepared.name();
        int finalSchema = prepared.dataSchemaVersion() == null
                ? dataset.getDataSchemaVersion() : prepared.dataSchemaVersion();
        String finalData = prepared.dataJson() == null ? dataset.getDataJson() : prepared.dataJson();
        long finalDataBytes = prepared.dataJson() == null
                ? dataset.getDataSizeBytes() : prepared.dataSizeBytes();
        int finalRows = prepared.dataJson() == null ? dataset.getRowCount() : prepared.rowCount();
        int finalColumns = prepared.dataJson() == null ? dataset.getColumnCount() : prepared.columnCount();
        boolean nameChanged = !finalName.equals(dataset.getName());
        boolean dataChanged = finalSchema != dataset.getDataSchemaVersion()
                || !finalData.equals(dataset.getDataJson());

        if (!nameChanged && !dataChanged) return outcome(dataset, List.of());
        if (nameChanged && datasets.existsByMember_IdAndNameAndIdNot(memberId, finalName, datasetId)) {
            throw new ApiException(HttpStatus.CONFLICT, "DATASET_NAME_ALREADY_USED",
                    "같은 이름의 데이터셋이 이미 있습니다.");
        }

        List<Chart> children = dataChanged ? charts.findAllByDatasetIdForUpdate(datasetId) : List.of();
        List<String> obsoleteKeys = new ArrayList<>();
        long invalidatedThumbnailBytes = 0;
        for (Chart child : children) {
            if (child.getThumbnailKey() != null) {
                obsoleteKeys.add(child.getThumbnailKey());
                invalidatedThumbnailBytes += child.getThumbnailSizeBytes();
            }
        }
        long currentStorage = datasets.calculateStorageBytes(memberId);
        long finalStorage = currentStorage - dataset.getDataSizeBytes()
                - invalidatedThumbnailBytes + finalDataBytes;
        if (finalStorage > maxStorageBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_LIMIT_EXCEEDED",
                    "회원 저장 용량을 초과했습니다.");
        }

        dataset.update(finalName, finalSchema, finalData, finalDataBytes, finalRows, finalColumns);
        if (dataChanged) {
            for (Chart child : children) if (child.getThumbnailKey() != null) child.clearThumbnail();
            charts.flush();
        }
        datasets.flush();
        return outcome(dataset, obsoleteKeys);
    }

    private UpdateOutcome outcome(Dataset dataset, List<String> obsoleteKeys) {
        return new UpdateOutcome(new DatasetPatchResponse(dataset.getId().toString(),
                Long.toString(dataset.getRevision()), obsoleteKeys.size(),
                dataset.getUpdatedAt().toInstant(ZoneOffset.UTC)), List.copyOf(obsoleteKeys));
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

    private ApiException revisionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "DATASET_REVISION_CONFLICT",
                "다른 저장 내용이 먼저 반영되었습니다. 최신 데이터셋을 다시 불러와 주세요.");
    }

    private record UpdateOutcome(DatasetPatchResponse response, List<String> obsoleteThumbnailKeys) {}
}
