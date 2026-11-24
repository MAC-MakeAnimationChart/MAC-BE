package com.macstudio.backend.dataset.service;

import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FE 소유 schemaVersion 1의 최소 표 구조와 저장할 JSON의 실제 크기를 검증한다. */
@Component
public class DatasetCommandValidator {
    private static final int MAX_COLUMNS = 1_000;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_CELL_TEXT = 10_000;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_NODES = 1_000_000;
    private static final long MAX_FIGURE_BYTES = 1_048_576L;
    private final JsonMapper json = JsonMapper.builder().build();

    public PreparedDatasetCreate validate(DatasetCreateCommand command, long maxDatasetBytes) {
        if (!command.name().equals(command.name().strip())) {
            throw bad("INVALID_DATASET_NAME", "데이터셋 이름의 앞뒤 공백을 제거해 주세요.");
        }
        if (command.dataSchemaVersion() != 1) {
            throw bad("UNSUPPORTED_DATA_SCHEMA_VERSION", "지원하지 않는 데이터 schemaVersion입니다.");
        }
        String dataJson = serialize(command.data());
        long dataBytes = dataJson.getBytes(StandardCharsets.UTF_8).length;
        if (dataBytes > maxDatasetBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATASET_TOO_LARGE",
                    "저장할 데이터 JSON 크기가 허용 범위를 초과했습니다.");
        }
        TableSize table = validateTable(command.data());

        JsonNode figure = command.firstChart().figure();
        if (!figure.isObject()) throw bad("INVALID_FIGURE_SCHEMA", "차트 설정은 JSON 객체여야 합니다.");
        validateTree(figure);
        String figureJson = serialize(figure);
        long figureBytes = figureJson.getBytes(StandardCharsets.UTF_8).length;
        if (figureBytes > MAX_FIGURE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FIGURE_TOO_LARGE",
                    "차트 설정 JSON 크기가 허용 범위를 초과했습니다.");
        }
        String title = command.firstChart().title() == null ? "" : command.firstChart().title();
        if (!title.equals(title.strip())) throw bad("INVALID_CHART_TITLE", "차트 제목의 앞뒤 공백을 제거해 주세요.");
        String chartType = command.firstChart().chartType();
        if (!chartType.equals(chartType.strip())) throw bad("INVALID_CHART_TYPE", "차트 종류를 확인해 주세요.");

        return new PreparedDatasetCreate(command.name(), command.dataSchemaVersion(), dataJson,
                dataBytes, table.rows(), table.columns(), title, chartType,
                command.firstChart().figureSchemaVersion(), figureJson, figureBytes);
    }

    private TableSize validateTable(JsonNode data) {
        if (!data.isObject()) throw bad("INVALID_DATA_SCHEMA", "데이터는 JSON 객체여야 합니다.");
        validateTree(data);
        JsonNode headers = data.get("headers");
        JsonNode rows = data.get("rows");
        if (headers == null || !headers.isArray() || headers.isEmpty() || headers.size() > MAX_COLUMNS
                || rows == null || !rows.isArray() || rows.size() > MAX_ROWS) {
            throw bad("INVALID_DATA_SCHEMA", "headers와 rows 표 구조를 확인해 주세요.");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode header : headers) {
            if (!header.isTextual() || header.asText().isBlank()
                    || header.asText().codePointCount(0, header.asText().length()) > 255
                    || !names.add(header.asText())) {
                throw bad("INVALID_DATA_SCHEMA", "헤더는 비어 있지 않은 고유 문자열이어야 합니다.");
            }
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != headers.size()) {
                throw bad("INVALID_DATA_SCHEMA", "모든 행의 셀 수는 헤더 수와 같아야 합니다.");
            }
            for (JsonNode cell : row) {
                if (!(cell.isNull() || cell.isTextual() || cell.isNumber() || cell.isBoolean())) {
                    throw bad("INVALID_DATA_SCHEMA", "셀에는 문자열·숫자·불리언·null만 저장할 수 있습니다.");
                }
                if (cell.isTextual()
                        && cell.asText().codePointCount(0, cell.asText().length()) > MAX_CELL_TEXT) {
                    throw bad("INVALID_DATA_SCHEMA", "셀 문자열이 너무 깁니다.");
                }
            }
        }
        return new TableSize(rows.size(), headers.size());
    }

    private void validateTree(JsonNode root) {
        int[] nodes = {0};
        visit(root, 1, nodes);
    }

    private void visit(JsonNode node, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MAX_JSON_DEPTH || nodes[0] > MAX_JSON_NODES) {
            throw bad("INVALID_JSON_COMPLEXITY", "JSON 구조가 허용 범위를 초과했습니다.");
        }
        for (JsonNode child : node) visit(child, depth + 1, nodes);
    }

    private String serialize(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (RuntimeException exception) {
            throw bad("INVALID_JSON", "JSON 데이터를 처리할 수 없습니다.");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record TableSize(int rows, int columns) {}

    public record PreparedDatasetCreate(
            String name, int dataSchemaVersion, String dataJson, long dataSizeBytes,
            int rowCount, int columnCount, String chartTitle, String chartType,
            int figureSchemaVersion, String figureJson, long figureSizeBytes
    ) {}
}
