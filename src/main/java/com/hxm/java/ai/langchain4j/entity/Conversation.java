package com.hxm.java.ai.langchain4j.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话元数据：一条记录对应一个会话（豆包式多会话）。
 * 会话的实际消息内容按 id（即 memoryId）隔离存储在 MongoDB 的 chat_messages 集合中。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;            // 对话 id，同时作为记忆的 memoryId

    private String title;       // 对话标题

    private LocalDateTime createTime;  // 创建时间

    private LocalDateTime updateTime;  // 最后更新时间（用于列表按最近活跃排序）
}
