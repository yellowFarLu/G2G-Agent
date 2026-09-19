package com.wikiagent.repo;

import com.wikiagent.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbDocumentRepo extends JpaRepository<KbDocument, String> {

    List<KbDocument> findAllByOrderByCreatedAtDesc();
}
