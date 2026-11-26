package com.macstudio.backend.dataset.repository;

import com.macstudio.backend.dataset.entity.Dataset;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {
    Optional<Dataset> findByMember_IdAndCreationRequestId(Long memberId, byte[] creationRequestId);

    boolean existsByMember_IdAndName(Long memberId, String name);

    boolean existsByMember_IdAndNameAndIdNot(Long memberId, String name, Long id);

    long countByMember_Id(Long memberId);

    /** 검색 조건을 적용한 회원 데이터셋만 정렬·페이지 처리한다. */
    @Query("select d from Dataset d where d.member.id = :memberId "
            + "and (:query = '' or lower(d.name) like lower(concat('%', :query, '%')))")
    Page<Dataset> findMyPageByMemberId(@Param("memberId") Long memberId,
            @Param("query") String query, Pageable pageable);

    /** 조회 API에서 회원 소유 데이터셋과 회원 상태를 함께 확인한다. */
    @Query("select d from Dataset d join fetch d.member m "
            + "where d.id = :datasetId and m.id = :memberId")
    Optional<Dataset> findOwnedById(@Param("datasetId") Long datasetId,
            @Param("memberId") Long memberId);

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
