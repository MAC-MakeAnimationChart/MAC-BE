package com.macstudio.backend.chart.entity;

import com.macstudio.backend.dataset.entity.Dataset;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.type.SqlTypes;

/** 데이터셋의 현재 데이터를 사용하는 차트 설정과 독립 revision을 저장한다. */
@Entity
@Getter
@Table(name = "chart")
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chart_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_chart_dataset"))
    private Dataset dataset;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "chart_type", nullable = false, length = 30)
    private String chartType;

    @Column(name = "figure_schema_version", nullable = false,
            columnDefinition = "SMALLINT UNSIGNED")
    private int figureSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "figure_json", nullable = false, columnDefinition = "JSON")
    private String figureJson;

    @Column(nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private long revision;

    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    @Column(name = "thumbnail_size_bytes", columnDefinition = "BIGINT UNSIGNED")
    private Long thumbnailSizeBytes;

    @Column(name = "thumbnail_content_type", length = 64)
    private String thumbnailContentType;

    @Column(name = "thumbnail_chart_revision", columnDefinition = "BIGINT UNSIGNED")
    private Long thumbnailChartRevision;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    /** FE가 전달한 초기 figure 설정으로 첫 차트 revision 1을 생성한다. */
    public static Chart create(Dataset dataset, String title, String chartType,
            int figureSchemaVersion, String figureJson) {
        Chart chart = new Chart();
        chart.dataset = Objects.requireNonNull(dataset, "dataset");
        chart.title = title == null ? "" : title;
        if (!chart.title.equals(chart.title.strip())
                || chart.title.codePointCount(0, chart.title.length()) > 255) {
            throw new IllegalArgumentException("Invalid chart title");
        }
        chart.chartType = requireText(chartType, 30, "chartType");
        if (figureSchemaVersion < 1) throw new IllegalArgumentException("Invalid figure schema version");
        chart.figureSchemaVersion = figureSchemaVersion;
        chart.figureJson = requireText(figureJson, Integer.MAX_VALUE, "figureJson");
        chart.revision = 1L;
        return chart;
    }

    /** 실제 옵션이 달라질 때만 revision을 증가시키고 기존 썸네일 참조를 무효화한다. */
    public void updateSettings(String title, String chartType, int figureSchemaVersion, String figureJson) {
        this.title = requireTitle(title);
        this.chartType = requireText(chartType, 30, "chartType");
        if (figureSchemaVersion < 1) throw new IllegalArgumentException("Invalid figure schema version");
        this.figureSchemaVersion = figureSchemaVersion;
        this.figureJson = requireText(figureJson, Integer.MAX_VALUE, "figureJson");
        this.revision = Math.addExact(this.revision, 1L);
        clearThumbnail();
        this.updatedAt = utcNow();
    }

    /** 현재 revision에서 검증된 새 썸네일 메타데이터를 결합한다. */
    public void attachThumbnail(String key, long sizeBytes, String contentType) {
        this.thumbnailKey = requireText(key, 512, "thumbnailKey");
        if (sizeBytes < 1) throw new IllegalArgumentException("Invalid thumbnail size");
        this.thumbnailSizeBytes = sizeBytes;
        this.thumbnailContentType = requireText(contentType, 64, "thumbnailContentType");
        this.thumbnailChartRevision = revision;
        this.updatedAt = utcNow();
    }

    /** 현재 썸네일 참조를 제거하고 S3 객체 삭제는 서비스 후처리에 맡긴다. */
    public void clearThumbnail() {
        this.thumbnailKey = null;
        this.thumbnailSizeBytes = null;
        this.thumbnailContentType = null;
        this.thumbnailChartRevision = null;
        this.updatedAt = utcNow();
    }

    @PrePersist
    private void prePersist() {
        LocalDateTime now = utcNow();
        createdAt = now;
        updatedAt = now;
    }

    private static String requireTitle(String value) {
        Objects.requireNonNull(value, "title");
        if (!value.equals(value.strip()) || value.codePointCount(0, value.length()) > 255) {
            throw new IllegalArgumentException("Invalid title");
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
