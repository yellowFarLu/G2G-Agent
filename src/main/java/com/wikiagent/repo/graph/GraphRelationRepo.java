package com.wikiagent.repo.graph;

import com.wikiagent.entity.graph.GraphRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface GraphRelationRepo extends JpaRepository<GraphRelation, String> {

    /** 按文档 ID 查询有效关系。 */
    List<GraphRelation> findBySourceDocIdAndActiveTrue(String docId);

    /** 按源实体查询有效关系（出边）。 */
    List<GraphRelation> findBySourceEntityIdAndActiveTrue(String sourceEntityId);

    /** 按目标实体查询有效关系（入边）。 */
    List<GraphRelation> findByTargetEntityIdAndActiveTrue(String targetEntityId);

    /** 按 ID 集合查询有效关系。 */
    List<GraphRelation> findByIdInAndActiveTrue(Collection<String> ids);

    /** 删除文档时软下线关联关系。 */
    @Modifying
    @Query("update GraphRelation r set r.active = false, r.updatedAt = CURRENT_TIMESTAMP where r.sourceDocId = :docId and r.active = true")
    int deactivateByDocId(@Param("docId") String docId);

    /** 统计有效关系数量。 */
    long countByActiveTrue();

    /** 查询某文档的全部关系（含 inactive 用于排查）。 */
    List<GraphRelation> findBySourceDocId(String docId);
}
