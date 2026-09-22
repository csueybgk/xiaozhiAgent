-- medical_documents 向量索引（HNSW）
-- 在 PostgreSQL 的 rag_db 库中、medical_documents 建表（首次灌库自动建表）后执行。
--
-- 为什么需要这条脚本：
--   langchain4j-pgvector 1.0.0-beta4 的 PgVectorEmbeddingStore 自动创建的索引是 IVFFlat
--   （CREATE INDEX ... USING ivfflat WITH (lists=N)），不支持 HNSW。
--   开发环境里手动改为 HNSW（召回率更好、无 lists 聚类重建问题），
--   此处固化该 DDL，保证任何环境克隆项目后能复现一致的索引。
--
-- 参数说明（pgvector 默认值，已足够当前语料规模；大规模语料建议按数据量调优）：
--   m                = 16    每个节点最大邻居数
--   ef_construction  = 64    建图时的动态候选集大小（越大图质量越高、建索引越慢）

CREATE INDEX IF NOT EXISTS medical_documents_embedding_hnsw
    ON medical_documents
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
