-- AI 对话元数据表
-- 在 MySQL（guiguxiaozhi 库）中执行

CREATE TABLE IF NOT EXISTS conversation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '对话 id（即记忆的 memoryId）',
    title       VARCHAR(100) DEFAULT '新对话' COMMENT '对话标题',
    create_time DATETIME COMMENT '创建时间',
    update_time DATETIME COMMENT '最后更新时间'
) COMMENT 'AI 对话元数据表';
