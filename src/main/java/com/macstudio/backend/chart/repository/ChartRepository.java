package com.macstudio.backend.chart.repository;

import com.macstudio.backend.chart.entity.Chart;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chart c where c.dataset.id = :datasetId order by c.id")
    List<Chart> findAllByDatasetIdForUpdate(@Param("datasetId") Long datasetId);
}
