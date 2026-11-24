package com.macstudio.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.FirstChartCreateCommand;
import com.macstudio.backend.dataset.service.DatasetCommandValidator;
import com.macstudio.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DatasetCommandValidatorTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final DatasetCommandValidator validator = new DatasetCommandValidator();

    @Test
    void calculatesNormalizedSizeAndTableDimensions() throws Exception {
        var prepared = validator.validate(command(
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10],[\"Feb\",20]]}"),
                1024 * 1024);

        assertThat(prepared.rowCount()).isEqualTo(2);
        assertThat(prepared.columnCount()).isEqualTo(2);
        assertThat(prepared.dataSizeBytes()).isEqualTo(prepared.dataJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(prepared.figureJson()).isEqualTo("{\"x\":\"month\",\"y\":\"sales\"}");
    }

    @Test
    void rejectsRowsWhoseCellCountDiffersFromHeaders() throws Exception {
        assertThatThrownBy(() -> validator.validate(command(
                "{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\"]]}"), 1024 * 1024))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_DATA_SCHEMA"));
    }

    @Test
    void rejectsDatasetLargerThanConfiguredLimit() throws Exception {
        assertThatThrownBy(() -> validator.validate(command(
                "{\"headers\":[\"x\"],\"rows\":[[\"long value\"]]}"), 10))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DATASET_TOO_LARGE"));
    }

    private DatasetCreateCommand command(String data) throws Exception {
        return new DatasetCreateCommand("sales", 1, json.readTree(data),
                new FirstChartCreateCommand("Sales chart", "BAR", 1,
                        json.readTree("{\"x\":\"month\",\"y\":\"sales\"}")));
    }
}
