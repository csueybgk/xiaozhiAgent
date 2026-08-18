package com.hxm.java.ai.langchain4j.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hxm.java.ai.langchain4j.entity.Conversation;
import com.hxm.java.ai.langchain4j.mapper.ConversationMapper;
import com.hxm.java.ai.langchain4j.service.ConversationService;
import com.hxm.java.ai.langchain4j.store.MongoChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Override
    public Long create(String title) {
        Conversation conv = new Conversation();
        conv.setTitle(title != null && !title.isBlank() ? title : "新对话");
        LocalDateTime now = LocalDateTime.now();
        conv.setCreateTime(now);
        conv.setUpdateTime(now);
        save(conv);
        return conv.getId();
    }

    @Override
    public List<Conversation> listAll() {
        return lambdaQuery()
                .orderByDesc(Conversation::getUpdateTime)
                .list();
    }

    @Override
    public void deleteConversation(Long id) {
        if (getById(id) == null) {
            return;
        }
        removeById(id);
        // 同步清除该对话在 MongoDB 中的聊天记忆
        mongoChatMemoryStore.deleteMessages(id);
    }

    @Override
    public void rename(Long id, String newTitle) {
        Conversation conv = getById(id);
        if (conv == null) {
            return;
        }
        conv.setTitle(newTitle);
        updateById(conv);
    }

    @Override
    public void touchUpdateTime(Long id) {
        Conversation conv = getById(id);
        if (conv != null) {
            conv.setUpdateTime(LocalDateTime.now());
            updateById(conv);
        }
    }
}
