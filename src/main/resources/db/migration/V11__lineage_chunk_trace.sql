-- V11__lineage_chunk_trace.sql: 子项目C2 chunk 溯源扩展
-- kb_child_chunk 增 page_no（页级溯源）；knowledge_metadata 增 page_no/snippet/artifact_id

ALTER TABLE kb_child_chunk ADD COLUMN IF NOT EXISTS page_no INT;

ALTER TABLE knowledge_metadata ADD COLUMN IF NOT EXISTS page_no INT;
ALTER TABLE knowledge_metadata ADD COLUMN IF NOT EXISTS snippet TEXT;
ALTER TABLE knowledge_metadata ADD COLUMN IF NOT EXISTS artifact_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_kb_child_doc_page ON kb_child_chunk (doc_id, page_no);
