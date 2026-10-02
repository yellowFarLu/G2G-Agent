package com.wikiagent.repo.lineage;

import com.wikiagent.entity.lineage.ProvenanceEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProvenanceEdgeRepo extends JpaRepository<ProvenanceEdgeEntity, Long> {

    List<ProvenanceEdgeEntity> findByDocIdAndVersionNoOrderByIdAsc(String docId, int versionNo);

    List<ProvenanceEdgeEntity> findByDocIdOrderByIdAsc(String docId);

    List<ProvenanceEdgeEntity> findByDocIdAndFromRef(String docId, String fromRef);

    List<ProvenanceEdgeEntity> findByDocIdAndToRef(String docId, String toRef);

    /** 边幂等：断点重跑/重放不重复追加同一条血缘边。 */
    boolean existsByDocIdAndVersionNoAndFromRefAndToRefAndEdgeType(
            String docId, int versionNo, String fromRef, String toRef, String edgeType);
}
