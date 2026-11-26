package com.macstudio.backend.chart.repository;

import com.macstudio.backend.chart.entity.Chart;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChartRepository extends JpaRepository<Chart, Long> {
    Optional<Chart> findFirstByDataset_IdOrderByIdAsc(Long datasetId);

    @Query("select c from Chart c join fetch c.dataset d join fetch d.member m "
            + "where c.id = :chartId and m.id = :memberId")
    Optional<Chart> findOwnedById(@Param("chartId") Long chartId, @Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chart c join fetch c.dataset d join fetch d.member m "
            + "where c.id = :chartId and m.id = :memberId")
    Optional<Chart> findOwnedByIdForUpdate(@Param("chartId") Long chartId, @Param("memberId") Long memberId);

    @Query("select c.thumbnailKey from Chart c where c.thumbnailKey is not null")
    List<String> findAllThumbnailKeys();

    boolean existsByThumbnailKey(String thumbnailKey);

    long countByDataset_Member_Id(Long memberId);

    /** 데이터셋 폴더 페이지에 표시할 차트 개수를 데이터셋별로 한 번에 집계한다. */
    @Query("select c.dataset.id as datasetId, count(c.id) as chartCount from Chart c "
            + "where c.dataset.id in :datasetIds group by c.dataset.id")
    List<DatasetChartCount> countByDatasetIds(@Param("datasetIds") List<Long> datasetIds);

    /** 선택 데이터셋의 차트 카드를 독립 페이지로 조회한다. */
    Page<Chart> findByDataset_Id(Long datasetId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chart c where c.dataset.id = :datasetId order by c.id")
    List<Chart> findAllByDatasetIdForUpdate(@Param("datasetId") Long datasetId);

    interface DatasetChartCount {
        Long getDatasetId();
        Long getChartCount();
    }
}
