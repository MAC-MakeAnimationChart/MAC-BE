package com.macstudio.backend.dataset.service;

import com.macstudio.backend.dataset.dto.DatasetPatchCommand;
import com.macstudio.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 데이터셋 PATCH의 선택 필드와 schemaVersion 1 표 구조를 검증한다. */
@Component
public class DatasetUpdateValidator {
    private static final int MAX_COLUMNS = 1_000;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_CELL_TEXT = 10_000;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_NODES = 1_000_000;
    private final JsonMapper json = JsonMapper.builder().build();

    public PreparedDatasetPatch validate(DatasetPatchCommand command, long maxDatasetBytes) {
        long expectedRevision = positiveLong(command.expectedRevision());
        if (command.name() != null && (command.name().isBlank()
                || !command.name().equals(command.name().strip()))) {
            throw bad("INVALID_DATASET_NAME", "데이터셋 이름을 확인해 주세요.");
        }
        if ((command.data() == null) != (command.dataSchemaVersion() == null)) {
            throw bad("INVALID_DATA_SCHEMA", "data와 dataSchemaVersion을 함께 전달해 주세요.");
        }
        if (command.data() == null) {
            return new PreparedDatasetPatch(expectedRevision, command.name(), null,
                    null, 0, 0, 0);
        }
        if (command.dataSchemaVersion() != 1) {
            throw bad("UNSUPPORTED_DATA_SCHEMA_VERSION", "지원하지 않는 데이터 schemaVersion입니다.");
        }
        if (!command.data().isObject()) {
            throw bad("INVALID_DATA_SCHEMA", "데이터는 JSON 객체여야 합니다.");
        }
        validateTree(command.data());
        TableSize table = validateTable(command.data());
        String dataJson;
        try {
            dataJson = json.writeValueAsString(command.data());
        } catch (RuntimeException exception) {
            throw bad("INVALID_JSON", "데이터 JSON을 처리할 수 없습니다.");
        }
        long bytes = dataJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxDatasetBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATASET_TOO_LARGE",
                    "저장할 데이터 JSON 크기가 허용 범위를 초과했습니다.");
        }
        return new PreparedDatasetPatch(expectedRevision, command.name(),
                command.dataSchemaVersion(), dataJson, bytes, table.rows(), table.columns());
    }

    private TableSize validateTable(JsonNode data) {
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

    private void validateTree(JsonNode node) {
        int[] count = {0};
        visit(node, 1, count);
    }

    private void visit(JsonNode node, int depth, int[] count) {
        count[0]++;
        if (depth > MAX_JSON_DEPTH || count[0] > MAX_JSON_NODES) {
            throw bad("INVALID_JSON_COMPLEXITY", "JSON 구조가 허용 범위를 초과했습니다.");
        }
        for (JsonNode child : node) visit(child, depth + 1, count);
    }

    private static long positiveLong(String value) {
        try {
            if (value == null || !value.equals(value.strip())) throw new NumberFormatException();
            long result = Long.parseLong(value);
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw bad("INVALID_DATASET_REVISION", "revision 값을 확인해 주세요.");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record TableSize(int rows, int columns) {}

    public record PreparedDatasetPatch(long expectedRevision, String name,
            Integer dataSchemaVersion, String dataJson, long dataSizeBytes,
            int rowCount, int columnCount) {}
}
