package com.macstudio.backend.dataset.repository;

import com.macstudio.backend.dataset.entity.Dataset;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {
    Optional<Dataset> findByMember_IdAndCreationRequestId(Long memberId, byte[] creationRequestId);

    boolean existsByMember_IdAndName(Long memberId, String name);

    boolean existsByMember_IdAndNameAndIdNot(Long memberId, String name, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dataset d join fetch d.member m "
            + "where d.id = :datasetId and m.id = :memberId")
    Optional<Dataset> findOwnedByIdForUpdate(@Param("datasetId") Long datasetId,
            @Param("memberId") Long memberId);

    /** 현재 데이터·차트 설정·썸네일의 서버 계산 저장량을 합산한다. */
    @Query(value = "select "
            + "coalesce((select sum(d.data_size_bytes) from dataset d where d.member_id = :memberId), 0) + "
            + "coalesce((select sum(octet_length(cast(c.figure_json as char character set utf8mb4))) "
            + "from chart c join dataset d on d.dataset_id = c.dataset_id where d.member_id = :memberId), 0) + "
            + "coalesce((select sum(c.thumbnail_size_bytes) from chart c join dataset d "
            + "on d.dataset_id = c.dataset_id where d.member_id = :memberId), 0)", nativeQuery = true)
    long calculateStorageBytes(@Param("memberId") Long memberId);
}
