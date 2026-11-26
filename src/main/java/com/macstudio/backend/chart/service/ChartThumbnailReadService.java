package com.macstudio.backend.chart.service;

import com.macstudio.backend.chart.entity.Chart;
import com.macstudio.backend.chart.repository.ChartRepository;
import com.macstudio.backend.global.exception.ApiException;
import com.macstudio.backend.storage.service.ChartThumbnailStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 차트 소유권을 확인한 뒤 private S3의 현재 썸네일 바이트만 제공한다. */
@Service
public class ChartThumbnailReadService {
    private final ChartRepository charts;
    private final ChartThumbnailStorage thumbnails;

    public ChartThumbnailReadService(ChartRepository charts, ChartThumbnailStorage thumbnails) {
        this.charts = charts;
        this.thumbnails = thumbnails;
    }

    public ThumbnailContent read(String subject, String chartIdValue) {
        long memberId = parsePositiveLong(subject, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        long chartId = parsePositiveLong(chartIdValue, HttpStatus.BAD_REQUEST, "INVALID_CHART_ID");
        Chart chart = charts.findOwnedById(chartId, memberId).orElseThrow(this::notFound);
        if (!chart.getDataset().getMember().isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "현재 회원을 인증할 수 없습니다.");
        }
        if (chart.getThumbnailKey() == null || chart.getThumbnailContentType() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "THUMBNAIL_NOT_FOUND",
                    "현재 썸네일을 찾을 수 없습니다.");
        }
        return new ThumbnailContent(thumbnails.download(chart.getThumbnailKey()),
                chart.getThumbnailContentType());
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

    public record ThumbnailContent(byte[] bytes, String contentType) {}
}
