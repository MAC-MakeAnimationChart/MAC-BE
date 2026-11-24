package com.macstudio.backend.dataset.service;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.DatasetCreateResponse;
import com.macstudio.backend.dataset.entity.Dataset;
import com.macstudio.backend.dataset.repository.DatasetRepository;
import com.macstudio.backend.dataset.service.DatasetCommandValidator.PreparedDatasetCreate;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.member.entity.Member;
import com.macstudio.backend.member.repository.MemberRepository;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증 회원의 최초 dataset + chart 생성 유스케이스를 한 DB 트랜잭션으로 조정한다. */
@Service
public class DatasetCreationService {
    private final MemberRepository members;
    private final DatasetRepository datasets;
    private final ChartRepository charts;
    private final DatasetCommandValidator validator;
    private final TransactionTemplate transaction;
    private final long maxDatasetBytes;
    private final long maxStorageBytes;

    public DatasetCreationService(MemberRepository members, DatasetRepository datasets,
            ChartRepository charts, DatasetCommandValidator validator,
            PlatformTransactionManager manager,
            @Value("${app.limits.member-upload-bytes}") long maxDatasetBytes,
            @Value("${app.limits.member-storage-bytes}") long maxStorageBytes) {
        this.members = members;
        this.datasets = datasets;
        this.charts = charts;
        this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
        this.maxDatasetBytes = maxDatasetBytes;
        this.maxStorageBytes = maxStorageBytes;
    }

    public DatasetCreateResponse create(String subject, String idempotencyKey,
            DatasetCreateCommand command) {
        long memberId = parseMemberId(subject);
        UUID requestId = parseRequestId(idempotencyKey);
        PreparedDatasetCreate prepared = validator.validate(command, maxDatasetBytes);
        return transaction.execute(status -> createInTransaction(memberId, requestId, prepared));
    }

    private DatasetCreateResponse createInTransaction(long memberId, UUID requestId,
            PreparedDatasetCreate prepared) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(this::unauthorized);
        if (!member.isActive()) throw unauthorized();
        byte[] requestBytes = Dataset.creationRequestBytes(requestId);
        var existing = datasets.findByMember_IdAndCreationRequestId(memberId, requestBytes);
        if (existing.isPresent()) return replay(existing.get(), prepared);

        if (datasets.existsByMember_IdAndName(memberId, prepared.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "DATASET_NAME_ALREADY_USED",
                    "같은 이름의 데이터셋이 이미 있습니다.");
        }
        long incomingBytes = Math.addExact(prepared.dataSizeBytes(), prepared.figureSizeBytes());
        long currentBytes = datasets.calculateStorageBytes(memberId);
        if (incomingBytes > maxStorageBytes || currentBytes > maxStorageBytes - incomingBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_LIMIT_EXCEEDED",
                    "회원 저장 용량을 초과했습니다.");
        }

        Dataset dataset = datasets.saveAndFlush(Dataset.create(member, requestId, prepared.name(),
                prepared.dataSchemaVersion(), prepared.dataJson(), prepared.dataSizeBytes(),
                prepared.rowCount(), prepared.columnCount()));
        Chart chart = charts.saveAndFlush(Chart.create(dataset, prepared.chartTitle(),
                prepared.chartType(), prepared.figureSchemaVersion(), prepared.figureJson()));
        return response(dataset, chart);
    }

    private DatasetCreateResponse replay(Dataset dataset, PreparedDatasetCreate prepared) {
        Chart chart = charts.findFirstByDataset_IdOrderByIdAsc(dataset.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "DATASET_STATE_INVALID", "저장된 데이터셋 상태를 확인할 수 없습니다."));
        boolean same = dataset.getName().equals(prepared.name())
                && dataset.getDataSchemaVersion() == prepared.dataSchemaVersion()
                && dataset.getDataJson().equals(prepared.dataJson())
                && chart.getTitle().equals(prepared.chartTitle())
                && chart.getChartType().equals(prepared.chartType())
                && chart.getFigureSchemaVersion() == prepared.figureSchemaVersion()
                && chart.getFigureJson().equals(prepared.figureJson());
        if (!same) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "같은 Idempotency-Key가 다른 생성 요청에 사용되었습니다.");
        }
        return response(dataset, chart);
    }

    private DatasetCreateResponse response(Dataset dataset, Chart chart) {
        return new DatasetCreateResponse(dataset.getId().toString(), Long.toString(dataset.getRevision()),
                chart.getId().toString(), Long.toString(chart.getRevision()),
                dataset.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    private long parseMemberId(String subject) {
        if (subject == null || !subject.matches("[0-9]+")) throw unauthorized();
        try {
            long id = Long.parseLong(subject);
            if (id <= 0) throw unauthorized();
            return id;
        } catch (NumberFormatException exception) {
            throw unauthorized();
        }
    }

    private UUID parseRequestId(String value) {
        try {
            if (value == null || !value.equals(value.strip())) throw new IllegalArgumentException();
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key에는 UUID를 전달해 주세요.");
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "현재 회원을 인증할 수 없습니다.");
    }
}
