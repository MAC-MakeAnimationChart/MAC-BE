package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.dto.ChartCreateCommand;
import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 새 차트의 필수 figure 설정과 렌더링 기준 dataset revision을 검증한다. */
@Component
public class ChartCreationValidator {
    private final ChartUpdateValidator figureValidator;

    public ChartCreationValidator(ChartUpdateValidator figureValidator) {
        this.figureValidator = figureValidator;
    }

    public PreparedChartCreate validate(ChartCreateCommand command) {
        long expectedDatasetRevision = ChartUpdateValidator.positiveLong(
                command.expectedDatasetRevision(), "INVALID_DATASET_REVISION");
        if (command.chartType() == null || command.chartType().isBlank()
                || command.figure() == null || command.figureSchemaVersion() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHART_CREATE",
                    "차트 종류와 figure 설정을 확인해 주세요.");
        }
        var prepared = figureValidator.validate(new ChartPatchCommand("1", command.title(),
                command.chartType(), command.figureSchemaVersion(), command.figure()));
        String title = prepared.title() == null ? "" : prepared.title();
        return new PreparedChartCreate(expectedDatasetRevision, title, prepared.chartType(),
                prepared.figureSchemaVersion(), prepared.figureJson(), prepared.figureSizeBytes());
    }

    public record PreparedChartCreate(
            long expectedDatasetRevision,
            String title,
            String chartType,
            int figureSchemaVersion,
            String figureJson,
            long figureSizeBytes
    ) {}
}
