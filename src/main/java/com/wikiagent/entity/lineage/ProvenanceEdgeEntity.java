package com.wikiagent.entity.lineage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "provenance_edge")
public class ProvenanceEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "from_ref", nullable = false, length = 128)
    private String fromRef;

    @Column(name = "from_type", nullable = false, length = 32)
    private String fromType;

    @Column(name = "to_ref", nullable = false, length = 128)
    private String toRef;

    @Column(name = "to_type", nullable = false, length = 32)
    private String toType;

    @Column(name = "edge_type", nullable = false, length = 24)
    private String edgeType;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public String getFromRef() { return fromRef; }
    public void setFromRef(String fromRef) { this.fromRef = fromRef; }
    public String getFromType() { return fromType; }
    public void setFromType(String fromType) { this.fromType = fromType; }
    public String getToRef() { return toRef; }
    public void setToRef(String toRef) { this.toRef = toRef; }
    public String getToType() { return toType; }
    public void setToType(String toType) { this.toType = toType; }
    public String getEdgeType() { return edgeType; }
    public void setEdgeType(String edgeType) { this.edgeType = edgeType; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
