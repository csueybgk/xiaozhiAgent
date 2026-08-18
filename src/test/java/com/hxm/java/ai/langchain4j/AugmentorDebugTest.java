package com.hxm.java.ai.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 诊断：打印完整检索增强器（Multi-Query 改写 → 混合检索 → 聚合）对指定问题
 * 实际检索到的片段文本，用于定位「直接检索满分、但答案仍错」的根因。
 */
@SpringBootTest(classes = XiaozhiApp.class)
public class AugmentorDebugTest {

    @Autowired
    @Qualifier("retrievalAugmentorXiaozhi")
    private RetrievalAugmentor retrievalAugmentor;

    private static final List<String> QUERIES = List.of(
            "神经内科每年门急诊接治多少患者？",   // [06] 16万人次  → 之前答错
            "协和医院东单院区的服务热线是多少？",   // [08] 热线      → 之前答错
            "协和医院内科学系有哪些科室？",         // [10] 内科      → 之前答错
            "协和医院外科学系有哪些科室？",         // [11] 外科      → 之前答错
            "协和医院东单院区的地址是什么？"        // [07] 帅府园    → 忠实度被误判
    );

    @Test
    public void dumpRetrievedContents() {
        for (String q : QUERIES) {
            ChatMessage userMsg = UserMessage.from(q);
            AugmentationResult result = retrievalAugmentor.augment(
                    new AugmentationRequest(userMsg, Metadata.from(userMsg, null, null)));
            System.out.println();
            System.out.println("================ 问题：" + q + " （检索到 " + result.contents().size() + " 段） ================");
            for (Content c : result.contents()) {
                String text = c.textSegment().text();
                System.out.printf("---[%.8s] %s%n", c.metadata(), text);
            }
        }
    }
}
