package com.wikiagent.repo.lineage;

import com.wikiagent.entity.lineage.FieldVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FieldVersionRepo extends JpaRepository<FieldVersionEntity, Long> {

    List<FieldVersionEntity> findByDocIdAndFieldKeyOrderByVersionNoAsc(String docId, String fieldKey);

    Optional<FieldVersionEntity> findByDocIdAndFieldKeyAndVersionNo(String docId, String fieldKey, int versionNo);

    List<FieldVersionEntity> findByDocIdAndVersionNo(String docId, int versionNo);
}
