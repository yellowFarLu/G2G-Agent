package com.wikiagent.repo.lineage;

import com.wikiagent.entity.lineage.DocVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocVersionRepo extends JpaRepository<DocVersionEntity, Long> {

    List<DocVersionEntity> findByDocIdOrderByVersionNoDesc(String docId);

    Optional<DocVersionEntity> findByDocIdAndVersionNo(String docId, int versionNo);

    Optional<DocVersionEntity> findFirstByDocIdOrderByVersionNoDesc(String docId);
}
