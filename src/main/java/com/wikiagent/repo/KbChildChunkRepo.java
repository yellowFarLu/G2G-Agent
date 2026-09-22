package com.wikiagent.repo;

import com.wikiagent.entity.KbChildChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KbChildChunkRepo extends JpaRepository<KbChildChunk, String> {

    List<KbChildChunk> findByDocId(String docId);

    List<KbChildChunk> findByIdIn(Collection<String> ids);

    /** 关键词包含匹配（Milvus 不可用时的本地 BM25 降级用，H2/MySQL 均兼容 LIKE）。 */
    List<KbChildChunk> findByContentContainingIgnoreCase(String keyword, Pageable pageable);

    void deleteByDocId(String docId);
}
