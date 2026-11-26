package com.macstudio.backend.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.macstudio.backend.chart.dto.ChartCreateCommand;
import com.macstudio.backend.chart.dto.ChartCreateResponse;
import com.macstudio.backend.chart.service.ChartCreationService;
import com.macstudio.backend.dataset.controller.DatasetController;
import com.macstudio.backend.dataset.dto.DatasetCreateCommand;
import com.macstudio.backend.dataset.dto.DatasetCreateResponse;
import com.macstudio.backend.dataset.dto.DatasetChartPageResponse;
import com.macstudio.backend.dataset.dto.FirstChartCreateCommand;
import com.macstudio.backend.dataset.service.DatasetCreationService;
import com.macstudio.backend.dataset.service.DatasetChartQueryService;
import com.macstudio.backend.dataset.service.DatasetUpdateService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

class DatasetControllerTests {
    @Test
    void delegatesAuthenticatedCommandAndReturnsCreatedLocation() throws Exception {
        DatasetCreationService service = mock(DatasetCreationService.class);
        DatasetController controller = new DatasetController(service, mock(DatasetUpdateService.class),
                mock(ChartCreationService.class), mock(DatasetChartQueryService.class));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(service.create(eq("7"), eq("d9428888-122b-4dcf-a2a5-3f196618b885"), any()))
                .thenReturn(new DatasetCreateResponse("11", "1", "21", "1",
                        Instant.parse("2026-11-24T10:00:00Z")));
        JsonMapper json = JsonMapper.builder().build();
        DatasetCreateCommand command = new DatasetCreateCommand("sales", 1,
                json.readTree("{\"headers\":[\"month\",\"sales\"],\"rows\":[[\"Jan\",10]]}"),
                new FirstChartCreateCommand("Chart", "BAR", 1,
                        json.readTree("{\"x\":\"month\",\"y\":\"sales\"}")));

        var result = controller.create(jwt, "d9428888-122b-4dcf-a2a5-3f196618b885", command);

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/v1/datasets/11");
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void delegatesExistingDatasetChartCreationAndReturnsChartLocation() throws Exception {
        ChartCreationService chartService = mock(ChartCreationService.class);
        DatasetController controller = new DatasetController(mock(DatasetCreationService.class),
                mock(DatasetUpdateService.class), chartService, mock(DatasetChartQueryService.class));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(chartService.create(eq("7"), eq("11"), any())).thenReturn(
                new ChartCreateResponse("11", "3", "25", "1",
                        Instant.parse("2026-11-26T11:00:00Z")));
        ChartCreateCommand command = new ChartCreateCommand("3", "Monthly sales", "BAR", 1,
                JsonMapper.builder().build().readTree("{\"x\":\"month\",\"y\":\"sales\"}"));

        var result = controller.createChart(jwt, "11", command);

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/v1/charts/25");
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void delegatesSelectedDatasetChartPageQuery() {
        DatasetChartQueryService queryService = mock(DatasetChartQueryService.class);
        DatasetController controller = new DatasetController(mock(DatasetCreationService.class),
                mock(DatasetUpdateService.class), mock(ChartCreationService.class), queryService);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        DatasetChartPageResponse expected = new DatasetChartPageResponse(
                new DatasetChartPageResponse.DatasetInfo("11", "sales", "3", 10, 2, 21,
                        Instant.parse("2026-11-26T11:00:00Z")),
                java.util.List.of(),
                new DatasetChartPageResponse.PageInfo(1, 20, 21, 2, false));
        when(queryService.get("7", "11", 1, 20)).thenReturn(expected);

        var result = controller.getCharts(jwt, "11", 1, 20);

        assertThat(result).isSameAs(expected);
    }
}
