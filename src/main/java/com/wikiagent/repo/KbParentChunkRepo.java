package com.wikiagent.repo;

import com.wikiagent.entity.KbParentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbParentChunkRepo extends JpaRepository<KbParentChunk, String> {

    List<KbParentChunk> findByDocIdOrderByParentIndex(String docId);

    void deleteByDocId(String docId);
}
