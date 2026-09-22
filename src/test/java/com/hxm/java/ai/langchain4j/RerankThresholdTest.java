package com.hxm.java.ai.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReRank 分数阈值的回归测试：验证阈值既能拦下「知识库不覆盖」的问题，又不会误伤域内问题。
 *
 * <p>这个测试是阈值取值的依据本身，而不只是校验。它同时承担两个作用：</p>
 * <ol>
 *   <li>记录为什么阈值取 0.1（域内 top1 下界 0.85 vs 域外 top1 上界 0.014）；</li>
 *   <li>一旦换 embedding 模型、换 ReRank 模型或改分块策略导致分数分布漂移，
 *       此测试会失败，提示重新标定阈值 —— 而不是让线上悄悄开始误拒或漏拦。</li>
 * </ol>
 *
 * <p>需要真实调用硅基流动 ReRank 接口，且依赖已灌库的 pgvector 语料。</p>
 */
@SpringBootTest(classes = XiaozhiApp.class)
public class RerankThresholdTest {

    @Autowired
    @Qualifier("hybridContentRetriever")
    private ContentRetriever hybridContentRetriever;

    /** 知识库里完全没有的领域，应当被阈值拦下 → 检索为空 → 走「说不知道」 */
    private static final List<String> OUT_OF_DOMAIN = List.of(
            "今天北京天气怎么样",
            "帮我写一个 Python 的快速排序",
            "推荐几部好看的科幻电影",
            "红烧肉怎么做才好吃",
            "如何准备考研数学",
            "介绍一下量子力学的基本原理",
            "帮我订一张去上海的高铁票",
            "比特币最近行情怎么样");

    @Test
    public void domainQuestionsSurviveThreshold() throws Exception {
        List<String> inDomain = loadEvalQueries();
        List<String> rejected = inDomain.stream()
                .filter(q -> hybridContentRetriever.retrieve(Query.from(q)).isEmpty())
                .collect(Collectors.toList());

        assertTrue(rejected.isEmpty(),
                "域内问题被阈值误拒（说明阈值偏高，或分数分布已漂移）: " + rejected);
        System.out.println("[阈值] 域内 " + inDomain.size() + " 个问题全部通过阈值");
    }

    @Test
    public void outOfDomainQuestionsAreRejected() {
        List<String> leaked = OUT_OF_DOMAIN.stream()
                .filter(q -> !hybridContentRetriever.retrieve(Query.from(q)).isEmpty())
                .collect(Collectors.toList());

        assertTrue(leaked.isEmpty(),
                "域外问题未被阈值拦下（说明阈值偏低，模型会基于无关资料作答）: " + leaked);
        System.out.println("[阈值] 域外 " + OUT_OF_DOMAIN.size() + " 个问题全部被拦下");
    }

    /** 被拦下时检索器必须返回空列表，注入器才会走「说不知道」分支；这是两者之间的契约 */
    @Test
    public void rejectionYieldsEmptyListNotSentinel() {
        for (String q : OUT_OF_DOMAIN) {
            List<Content> contents = hybridContentRetriever.retrieve(Query.from(q));
            assertTrue(contents.isEmpty(),
                    "被拦下时应返回空列表（触发兜底注入），实际: " + contents.size() + " 条, query=" + q);
            assertFalse(contents.stream().anyMatch(c -> "【无需检索】".equals(c.textSegment().text())),
                    "空列表不应混入「无需检索」哨兵，否则会被注入器误判为普通闲聊放行");
        }
    }

    private List<String> loadEvalQueries() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/rag-eval.json")) {
            List<EvalItem> items = new ObjectMapper().readValue(in, new TypeReference<List<EvalItem>>() {});
            return items.stream().map(i -> i.query).collect(Collectors.toList());
        }
    }

    static class EvalItem {
        public String query;
        public List<String> expected;
    }
}
