package com.macstudio.backend.dataset.entity;

import com.macstudio.backend.member.entity.Member;
import jakarta.persistence.*;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.type.SqlTypes;

/** 회원이 소유한 현재 편집 데이터 JSON과 독립 revision을 저장한다. */
@Entity
@Getter
@Table(name = "dataset", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dataset_member_name", columnNames = {"member_id", "name"}),
        @UniqueConstraint(name = "uk_dataset_member_creation_request",
                columnNames = {"member_id", "creation_request_id"})
})
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dataset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dataset_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_dataset_member"))
    private Member member;

    @Getter(AccessLevel.NONE)
    @Column(name = "creation_request_id", nullable = false, updatable = false,
            columnDefinition = "BINARY(16)")
    private byte[] creationRequestId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "data_schema_version", nullable = false,
            columnDefinition = "SMALLINT UNSIGNED")
    private int dataSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_json", nullable = false, columnDefinition = "JSON")
    private String dataJson;

    @Column(name = "data_size_bytes", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private long dataSizeBytes;

    @Column(name = "row_count", nullable = false, columnDefinition = "INT UNSIGNED")
    private int rowCount;

    @Column(name = "column_count", nullable = false, columnDefinition = "INT UNSIGNED")
    private int columnCount;

    @Column(nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private long revision;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    /** 검증 완료된 최초 저장 명령으로 데이터셋 revision 1을 생성한다. */
    public static Dataset create(Member member, UUID creationRequestId, String name,
            int schemaVersion, String dataJson, long dataSizeBytes, int rowCount, int columnCount) {
        Dataset dataset = new Dataset();
        dataset.member = Objects.requireNonNull(member, "member");
        dataset.creationRequestId = uuidBytes(Objects.requireNonNull(creationRequestId, "creationRequestId"));
        dataset.name = requireText(name, 120, "name");
        if (schemaVersion < 1 || dataSizeBytes < 1 || rowCount < 0 || columnCount < 1) {
            throw new IllegalArgumentException("Invalid dataset metadata");
        }
        dataset.dataSchemaVersion = schemaVersion;
        dataset.dataJson = requireText(dataJson, Integer.MAX_VALUE, "dataJson");
        dataset.dataSizeBytes = dataSizeBytes;
        dataset.rowCount = rowCount;
        dataset.columnCount = columnCount;
        dataset.revision = 1L;
        return dataset;
    }

    /** 멱등성 키 조회에 사용할 원시 UUID 16바이트의 방어적 복사본을 반환한다. */
    public byte[] getCreationRequestId() {
        return creationRequestId.clone();
    }

    /** 같은 최초 생성 명령인지 비교할 수 있도록 UUID를 복원한다. */
    public UUID creationRequestUuid() {
        ByteBuffer buffer = ByteBuffer.wrap(creationRequestId);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** 하위 차트 저장도 데이터셋의 마지막 사용 시각에 반영한다. */
    public void touch() {
        updatedAt = utcNow();
    }

    /** 실제 이름 또는 표 데이터가 바뀐 한 번의 저장에서 revision을 한 번만 증가시킨다. */
    public void update(String name, int schemaVersion, String dataJson, long dataSizeBytes,
            int rowCount, int columnCount) {
        this.name = requireText(name, 120, "name");
        if (schemaVersion < 1 || dataSizeBytes < 1 || rowCount < 0 || columnCount < 1) {
            throw new IllegalArgumentException("Invalid dataset metadata");
        }
        this.dataSchemaVersion = schemaVersion;
        this.dataJson = requireText(dataJson, Integer.MAX_VALUE, "dataJson");
        this.dataSizeBytes = dataSizeBytes;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.revision = Math.addExact(this.revision, 1L);
        this.updatedAt = utcNow();
    }

    @PrePersist
    private void prePersist() {
        LocalDateTime now = utcNow();
        createdAt = now;
        updatedAt = now;
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static byte[] creationRequestBytes(UUID value) {
        return Arrays.copyOf(uuidBytes(value), 16);
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
