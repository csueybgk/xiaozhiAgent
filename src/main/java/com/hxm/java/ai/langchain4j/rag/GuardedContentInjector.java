package com.hxm.java.ai.langchain4j.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;

import java.util.List;

/**
 * 带兜底的内容注入器：检索到内容时走默认注入；检索为空时，把「如实说不知道」的指令注入用户消息，
 * 防止模型靠自身记忆裸答（医疗场景下尤其危险——之前就发生过没检索到却编错服务热线的情况）。
 *
 * <p>区分两种空检索：</p>
 * <ul>
 *   <li><b>非医疗问题</b>（打招呼、身份、闲聊等）：查询改写器输出「【无需检索】」，
 *       检索器返回该哨兵内容，此处原样放行、正常回答；</li>
 *   <li><b>医疗问题但没检索到</b>：contents 为空，注入「无法回答」指令。</li>
 * </ul>
 */
public class GuardedContentInjector implements ContentInjector {

    private static final ContentInjector DELEGATE = new DefaultContentInjector();

    // 查询改写器对非医疗问题输出的标记（与 HybridContentRetriever.NO_RETRIEVAL 一致）
    private static final String NO_RETRIEVAL = "【无需检索】";

    // 兜底指令前缀复用默认注入器的标记，使历史记录清洗逻辑（stripRagContent）能一并去掉这段内容
    private static final String RAG_CONTENT_MARKER = "Answer using the following information:";

    private static final String NO_RETRIEVAL_HINT =
            "（系统提示：知识库中未检索到与该问题相关的资料。你必须直接、明确地告诉用户："
                    + "抱歉，我目前的知识库里没有相关信息，无法回答这个问题。"
                    + "绝对不要根据你自身的知识猜测、补充或编造答案。）";

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage userMessage) {
        if (contents == null || contents.isEmpty()) {
            String question = textOf(userMessage);
            return UserMessage.from(question + "\n\n" + RAG_CONTENT_MARKER + "\n" + NO_RETRIEVAL_HINT);
        }
        // 非医疗问题（检索器返回的哨兵内容）→ 原样放行，正常回答
        if (contents.stream().anyMatch(c -> NO_RETRIEVAL.equals(c.textSegment().text()))) {
            return userMessage;
        }
        return DELEGATE.inject(contents, userMessage);
    }

    private String textOf(ChatMessage message) {
        if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        return message == null ? "" : message.toString();
    }
}
