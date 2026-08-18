package com.hxm.java.ai.langchain4j;

import com.hxm.java.ai.langchain4j.rag.GuardedContentInjector;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GuardedContentInjector 的确定性单元测试（不依赖 LLM / Spring 上下文）。
 * 覆盖三种注入场景：检索为空（兜底）、非医疗问题（放行）、检索有内容（默认注入）。
 */
class GuardedContentInjectorTest {

    private final GuardedContentInjector injector = new GuardedContentInjector();

    @Test
    void emptyRetrievalInjectsDeclineDirective() {
        ChatMessage result = injector.inject(List.of(), UserMessage.from("神经内科有多少张床位？"));
        String text = ((UserMessage) result).singleText();

        assertTrue(text.contains("神经内科有多少张床位"), "应保留用户原始问题");
        assertTrue(text.contains("无法回答"), "应注入「无法回答」兜底指令");
        assertTrue(text.contains("Answer using the following information:"), "应复用默认标记，供历史记录清洗");
    }

    @Test
    void nonMedicalQuestionPassesThrough() {
        UserMessage original = UserMessage.from("你是谁");
        ChatMessage result = injector.inject(List.of(Content.from("【无需检索】")), original);

        assertSame(original, result, "非医疗问题应原样放行，正常回答");
    }

    @Test
    void nonEmptyRetrievalDelegatesToDefault() {
        ChatMessage result = injector.inject(
                List.of(Content.from("北京协和医院神经内科成立于1921年")),
                UserMessage.from("神经内科成立于哪一年？"));
        String text = ((UserMessage) result).singleText();

        assertTrue(text.contains("成立于1921年"), "应注入检索到的内容");
        assertTrue(text.contains("Answer using the following information:"), "应使用默认注入模板");
    }
}
