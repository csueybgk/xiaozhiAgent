package com.hxm.java.ai.langchain4j.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 分层记忆：滑动窗口 + 摘要压缩。
 * <p>当对话消息数超过 {@code maxMessages} 时，把最旧的消息连同已有摘要喂给 LLM 压缩成新摘要；
 * 摘要以 SystemMessage 形式保存在消息列表最前面，只保留最近 {@code keepRecent} 条完整消息。</p>
 * <p>这样在上下文长度有限的情况下，既能保留早期对话的关键信息（摘要层），又不丢近期细节（窗口层）。</p>
 */
public class SummaryChatMemory implements ChatMemory {

    private static final String SUMMARY_PREFIX = "以下是此前对话的摘要，请基于它继续回答用户问题：\n";

    // RAG 检索会把内容拼进用户消息（形如 "问题\n\nAnswer using the following information:\n<内容>"），
    // 存储前只保留用户真实提问，避免检索内容污染记忆、摘要压缩和历史记录
    private static final String RAG_CONTENT_MARKER = "Answer using the following information:";

    private final Object id;
    private final ChatMemoryStore store;
    private final ChatModel summaryModel;
    private final int maxMessages;   // 消息窗口上限，超过则触发压缩
    private final int keepRecent;    // 压缩后保留的最近消息条数

    public SummaryChatMemory(Object id, ChatMemoryStore store, ChatModel summaryModel,
                             int maxMessages, int keepRecent) {
        this.id = id;
        this.store = store;
        this.summaryModel = summaryModel;
        this.maxMessages = maxMessages;
        this.keepRecent = keepRecent;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        message = stripRagContent(message);
        List<ChatMessage> all = new ArrayList<>(store.getMessages(id));

        // 摘要约定：消息列表第一项若是 SystemMessage，即为此前生成的摘要
        String oldSummary = null;
        int start = 0;
        if (!all.isEmpty() && all.get(0) instanceof SystemMessage) {
            oldSummary = ((SystemMessage) all.get(0)).text();
            start = 1;
        }

        List<ChatMessage> conversation = new ArrayList<>(all.subList(start, all.size()));
        conversation.add(message);

        if (conversation.size() > maxMessages) {
            // 超过窗口：把最旧的 (size - keepRecent) 条压进摘要，保留最近 keepRecent 条
            int overflow = conversation.size() - keepRecent;
            List<ChatMessage> toSummarize = new ArrayList<>(conversation.subList(0, overflow));
            List<ChatMessage> recent = new ArrayList<>(conversation.subList(overflow, conversation.size()));

            String newSummary = summarize(oldSummary, toSummarize);

            List<ChatMessage> newAll = new ArrayList<>();
            if (newSummary != null && !newSummary.isBlank()) {
                newAll.add(SystemMessage.from(SUMMARY_PREFIX + newSummary));
            }
            newAll.addAll(recent);
            store.updateMessages(id, newAll);
        } else {
            List<ChatMessage> newAll = new ArrayList<>();
            if (oldSummary != null && !oldSummary.isBlank()) {
                newAll.add(SystemMessage.from(oldSummary));
            }
            newAll.addAll(conversation);
            store.updateMessages(id, newAll);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 去掉 RAG 注入的检索内容，只保留用户真实提问。
     */
    private ChatMessage stripRagContent(ChatMessage message) {
        if (!(message instanceof UserMessage userMessage) || !userMessage.hasSingleText()) {
            return message;
        }
        String text = userMessage.singleText();
        int idx = text.indexOf(RAG_CONTENT_MARKER);
        if (idx < 0) {
            return message;
        }
        String realQuestion = text.substring(0, idx).trim();
        return realQuestion.isBlank() ? message : UserMessage.from(realQuestion);
    }

    /**
     * 增量摘要：把「已有摘要 + 需要压缩的消息」喂给 LLM，生成更新后的摘要。
     */
    private String summarize(String oldSummary, List<ChatMessage> toSummarize) {
        String pureOld = oldSummary == null ? null
                : oldSummary.startsWith(SUMMARY_PREFIX) ? oldSummary.substring(SUMMARY_PREFIX.length()) : oldSummary;

        StringBuilder convo = new StringBuilder();
        for (ChatMessage m : toSummarize) {
            String line = format(m);
            if (line != null && !line.isBlank()) {
                convo.append(line).append("\n");
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("请将下面这段对话压缩成简洁的摘要，作为后续对话的上下文。\n")
                .append("要求：\n")
                .append("1. 保留关键信息：用户的核心诉求、症状、重要事实、已做的判断或预约、未完成的事项；\n")
                .append("2. 客观、简洁，用要点或短句，不要添加对话中不存在的内容；\n")
                .append("3. 直接输出摘要内容本身，不要任何前缀或解释。\n");
        if (pureOld != null && !pureOld.isBlank()) {
            prompt.append("\n【已有摘要】\n").append(pureOld).append("\n");
        }
        prompt.append("\n【新增对话】\n").append(convo)
                .append("\n请输出更新后的摘要：");

        try {
            String result = summaryModel.chat(prompt.toString());
            return result == null ? "" : result.trim();
        } catch (Exception e) {
            // 摘要失败兜底：返回旧摘要，不影响正常对话
            return pureOld == null ? "" : pureOld;
        }
    }

    private String format(ChatMessage m) {
        if (m instanceof UserMessage u) {
            return "用户：" + (u.hasSingleText() ? u.singleText() : u.contents().toString());
        }
        if (m instanceof AiMessage a) {
            return a.text() == null ? "助手：" : "助手：" + a.text();
        }
        if (m instanceof SystemMessage s) {
            return "系统：" + s.text();
        }
        return m.toString();
    }
}
