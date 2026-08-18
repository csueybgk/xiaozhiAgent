package com.hxm.java.ai.langchain4j.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hxm.java.ai.langchain4j.entity.Conversation;

import java.util.List;

public interface ConversationService extends IService<Conversation> {

    /** 创建新对话，返回新对话 id */
    Long create(String title);

    /** 查询所有对话（按最后更新时间倒序） */
    List<Conversation> listAll();

    /** 删除对话，同时清除 MongoDB 中该对话的记忆 */
    void deleteConversation(Long id);

    /** 重命名对话 */
    void rename(Long id, String newTitle);

    /** 刷新对话的最后更新时间（每次发消息时调用，使列表按最近活跃排序） */
    void touchUpdateTime(Long id);
}
