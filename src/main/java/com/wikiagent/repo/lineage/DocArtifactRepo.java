package com.wikiagent.repo.lineage;

import com.wikiagent.entity.lineage.DocArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocArtifactRepo extends JpaRepository<DocArtifactEntity, Long> {

    List<DocArtifactEntity> findByDocIdAndVersionNoOrderByIdAsc(String docId, int versionNo);

    List<DocArtifactEntity> findByDocIdOrderByVersionNoAscIdAsc(String docId);

    Optional<DocArtifactEntity> findByDocIdAndVersionNoAndArtifactTypeAndPageNo(
            String docId, int versionNo, String artifactType, Integer pageNo);
}
