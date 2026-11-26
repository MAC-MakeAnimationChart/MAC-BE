package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.dto.ChartCreateCommand;
import com.macstudio.backend.chart.dto.ChartCreateResponse;
import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.chart.service.ChartCreationValidator.PreparedChartCreate;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.global.exception.ApiException;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 기존 데이터셋의 소유권·revision·저장량을 확인하고 새 차트 생성을 조정한다. */
@Service
public class ChartCreationService {
    private final DatasetRepository datasets;
    private final ChartRepository charts;
    private final ChartCreationValidator validator;
    private final TransactionTemplate transaction;
    private final long maxStorageBytes;

    public ChartCreationService(DatasetRepository datasets, ChartRepository charts,
            ChartCreationValidator validator, PlatformTransactionManager manager,
            @Value("${app.limits.member-storage-bytes}") long maxStorageBytes) {
        this.datasets = datasets;
        this.charts = charts;
        this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
        this.maxStorageBytes = maxStorageBytes;
    }

    public ChartCreateResponse create(String subject, String datasetIdValue, ChartCreateCommand command) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long datasetId = parsePositiveLong(datasetIdValue, HttpStatus.BAD_REQUEST, "INVALID_DATASET_ID");
        PreparedChartCreate prepared = validator.validate(command);
        ChartCreateResponse response = transaction.execute(status ->
                createInTransaction(memberId, datasetId, prepared));
        if (response == null) throw new IllegalStateException("Chart creation transaction returned no result");
        return response;
    }

    /** 데이터셋을 잠근 상태에서 stale 데이터 기반 생성과 회원 저장량 초과를 차단한다. */
    private ChartCreateResponse createInTransaction(long memberId, long datasetId,
            PreparedChartCreate prepared) {
        Dataset dataset = datasets.findOwnedByIdForUpdate(datasetId, memberId).orElseThrow(this::notFound);
        if (!dataset.getMember().isActive()) throw unauthorized();
        if (dataset.getRevision() != prepared.expectedDatasetRevision()) throw revisionConflict();

        long currentStorage = datasets.calculateStorageBytes(memberId);
        if (prepared.figureSizeBytes() > maxStorageBytes
                || currentStorage > maxStorageBytes - prepared.figureSizeBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_LIMIT_EXCEEDED",
                    "회원 저장 용량을 초과했습니다.");
        }

        Chart chart = charts.saveAndFlush(Chart.create(dataset, prepared.title(),
                prepared.chartType(), prepared.figureSchemaVersion(), prepared.figureJson()));
        dataset.touch();
        datasets.flush();
        return new ChartCreateResponse(dataset.getId().toString(), Long.toString(dataset.getRevision()),
                chart.getId().toString(), Long.toString(chart.getRevision()),
                chart.getCreatedAt().toInstant(ZoneOffset.UTC));
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
                "데이터가 변경되었습니다. 최신 데이터셋을 다시 불러와 주세요.");
    }
}
