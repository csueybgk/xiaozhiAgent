package com.hxm.java.ai.langchain4j.controller;

import com.hxm.java.ai.langchain4j.assistant.XiaozhiAgent;
import com.hxm.java.ai.langchain4j.bean.ChatForm;
import com.hxm.java.ai.langchain4j.entity.Conversation;
import com.hxm.java.ai.langchain4j.service.ConversationService;
import com.hxm.java.ai.langchain4j.store.MongoChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "硅谷小智")
@RestController
@RequestMapping("/xiaozhi")
public class XiaozhiController {

    @Autowired
    private XiaozhiAgent xiaozhiAgent;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Operation(summary = "对话")
    @PostMapping(value = "/chat", produces = "text/stream;charset=utf-8")
    public Flux<String> chat(@RequestBody ChatForm chatForm, HttpServletResponse response) {
        Long conversationId = chatForm.getMemoryId();
        if (conversationId == null || conversationId == 0) {
            // 新对话：自动创建，标题取消息前 20 字
            String msg = chatForm.getMessage() == null ? "" : chatForm.getMessage().trim();
            String title = msg.length() > 20 ? msg.substring(0, 20) : msg;
            conversationId = conversationService.create(title.isBlank() ? "新对话" : title);
            // 流式接口无法在响应体里带 id，通过响应头返回给前端
            response.setHeader("X-Conversation-Id", String.valueOf(conversationId));
        } else {
            conversationService.touchUpdateTime(conversationId);
        }
        final Long cid = conversationId;
        return xiaozhiAgent.chat(cid, chatForm.getMessage());
    }

    @Operation(summary = "对话列表")
    @GetMapping("/conversations")
    public List<Conversation> listConversations() {
        return conversationService.listAll();
    }

    @Operation(summary = "新建对话")
    @PostMapping("/conversations")
    public Conversation createConversation(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "新对话");
        return conversationService.getById(conversationService.create(title));
    }

    @Operation(summary = "删除对话")
    @DeleteMapping("/conversations/{id}")
    public String deleteConversation(@PathVariable("id") Long id) {
        conversationService.deleteConversation(id);
        return "ok";
    }

    @Operation(summary = "重命名对话")
    @PutMapping("/conversations/{id}")
    public String renameConversation(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        conversationService.rename(id, body.getOrDefault("title", "新对话"));
        return "ok";
    }

    @Operation(summary = "查询对话消息历史")
    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable("id") Long id) {
        List<ChatMessage> messages = mongoChatMemoryStore.getMessages(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            try {
                if (msg instanceof UserMessage) {
                    String content = stripRagContent(((UserMessage) msg).singleText());
                    if (content != null && !content.isBlank()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("isUser", true);
                        item.put("content", content);
                        result.add(item);
                    }
                } else if (msg instanceof AiMessage) {
                    String content = ((AiMessage) msg).text();
                    if (content != null && !content.isBlank()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("isUser", false);
                        item.put("content", content);
                        result.add(item);
                    }
                }
            } catch (Exception ignored) {
                // 跳过无法解析的消息类型（如工具调用消息）
            }
        }
        return result;
    }

    // RAG 检索会把内容拼进用户消息（形如 "问题\n\nAnswer using the following information:\n<内容>"），
    // 返回历史时只保留用户真实提问，避免把检索到的知识库内容当成用户提问展示
    private static final String RAG_CONTENT_MARKER = "Answer using the following information:";

    private String stripRagContent(String content) {
        if (content == null) return null;
        int idx = content.indexOf(RAG_CONTENT_MARKER);
        if (idx < 0) return content;
        String real = content.substring(0, idx).trim();
        return real.isBlank() ? content : real;
    }
}
