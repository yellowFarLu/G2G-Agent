package com.wikiagent.repo.lineage;

import com.wikiagent.entity.lineage.ExtractedFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExtractedFieldRepo extends JpaRepository<ExtractedFieldEntity, Long> {

    List<ExtractedFieldEntity> findByDocIdOrderByFieldKeyAsc(String docId);

    Optional<ExtractedFieldEntity> findByDocIdAndFieldKey(String docId, String fieldKey);
}
