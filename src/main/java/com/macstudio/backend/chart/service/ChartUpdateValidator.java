package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.dto.ChartPatchCommand;
import com.macstudio.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 부분 수정 필드와 전체 figure JSON의 구조·복잡도·실제 크기를 검증한다. */
@Component
public class ChartUpdateValidator {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 1_000_000;
    private static final long MAX_FIGURE_BYTES = 1_048_576L;
    private final JsonMapper json = JsonMapper.builder().build();

    public PreparedChartPatch validate(ChartPatchCommand command) {
        long expectedRevision = positiveLong(command.expectedRevision(), "INVALID_CHART_REVISION");
        if (command.title() != null && !command.title().equals(command.title().strip())) {
            throw bad("INVALID_CHART_TITLE", "차트 제목의 앞뒤 공백을 제거해 주세요.");
        }
        if (command.chartType() != null
                && (command.chartType().isBlank() || !command.chartType().equals(command.chartType().strip()))) {
            throw bad("INVALID_CHART_TYPE", "차트 종류를 확인해 주세요.");
        }
        if ((command.figure() == null) != (command.figureSchemaVersion() == null)) {
            throw bad("INVALID_FIGURE_SCHEMA", "figure와 figureSchemaVersion을 함께 전달해 주세요.");
        }
        String figureJson = null;
        long figureBytes = 0;
        if (command.figure() != null) {
            if (command.figureSchemaVersion() < 1 || !command.figure().isObject()) {
                throw bad("INVALID_FIGURE_SCHEMA", "차트 설정 JSON과 schemaVersion을 확인해 주세요.");
            }
            validateTree(command.figure());
            try {
                figureJson = json.writeValueAsString(command.figure());
            } catch (RuntimeException exception) {
                throw bad("INVALID_JSON", "차트 설정 JSON을 처리할 수 없습니다.");
            }
            figureBytes = figureJson.getBytes(StandardCharsets.UTF_8).length;
            if (figureBytes > MAX_FIGURE_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FIGURE_TOO_LARGE",
                        "차트 설정 JSON 크기가 허용 범위를 초과했습니다.");
            }
        }
        return new PreparedChartPatch(expectedRevision, command.title(), command.chartType(),
                command.figureSchemaVersion(), figureJson, figureBytes);
    }

    private void validateTree(JsonNode root) {
        int[] nodes = {0};
        visit(root, 1, nodes);
    }

    private void visit(JsonNode node, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MAX_DEPTH || nodes[0] > MAX_NODES) {
            throw bad("INVALID_JSON_COMPLEXITY", "차트 설정 JSON 구조가 허용 범위를 초과했습니다.");
        }
        for (JsonNode child : node) visit(child, depth + 1, nodes);
    }

    static long positiveLong(String value, String code) {
        try {
            if (value == null || !value.equals(value.strip())) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw bad(code, "revision 값을 확인해 주세요.");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record PreparedChartPatch(long expectedRevision, String title, String chartType,
            Integer figureSchemaVersion, String figureJson, long figureSizeBytes) {}
}
