package com.wikiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kb_child_chunk")
public class KbChildChunk {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "parent_id", nullable = false, length = 64)
    private String parentId;

    @Column(name = "child_index", nullable = false)
    private int childIndex;

    @Column(nullable = false, length = 4000)
    private String content;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public int getChildIndex() { return childIndex; }
    public void setChildIndex(int childIndex) { this.childIndex = childIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
