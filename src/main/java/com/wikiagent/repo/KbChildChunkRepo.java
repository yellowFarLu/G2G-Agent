package com.wikiagent.repo;

import com.wikiagent.entity.KbChildChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KbChildChunkRepo extends JpaRepository<KbChildChunk, String> {

    List<KbChildChunk> findByDocId(String docId);

    List<KbChildChunk> findByIdIn(Collection<String> ids);

    void deleteByDocId(String docId);
}
