package com.kyuloud.ai.rag.retrieval;

import com.kyuloud.ai.config.RagProperties;
import com.kyuloud.ai.rag.dto.RagChatResponse;
import com.kyuloud.ai.rag.dto.RagChatResponse.Citation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Phase 2b — RAG 질의 서비스.
 *
 * <p>{@link RetrievalAugmentationAdvisor}(모듈형 RAG)로 검색·증강을 수행한다:
 * <ul>
 *   <li>{@link RewriteQueryTransformer} 로 사용자 질의를 검색 친화적으로 재작성</li>
 *   <li>{@link VectorStoreDocumentRetriever} 로 topK / similarityThreshold 기반 검색</li>
 * </ul>
 * 답변과 함께 근거가 된 출처(citation)를 반환한다.
 */
@Slf4j
@Service
public class RagChatService {

    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final int SNIPPET_MAX_LENGTH = 160;

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

    @Value("classpath:prompts/system-rag.st")
    private Resource ragSystemPrompt;

    public RagChatService(ChatClient chatClient,
                          ChatClient.Builder chatClientBuilder,
                          VectorStore vectorStore,
                          RagProperties ragProperties) {
        this.chatClient = chatClient;
        this.chatClientBuilder = chatClientBuilder;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
    }

    public RagChatResponse chat(String conversationId, String message) {
        String cid = StringUtils.hasText(conversationId) ? conversationId : DEFAULT_CONVERSATION_ID;
        log.debug("rag chat: cid={}, message={}, topK={}, threshold={}",
                cid, message, ragProperties.getTopK(), ragProperties.getSimilarityThreshold());

        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(chatClientBuilder.clone())
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(ragProperties.getTopK())
                        .similarityThreshold(ragProperties.getSimilarityThreshold())
                        .build())
                .build();

        String reply = chatClient.prompt()
                .system(ragSystemPrompt)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .advisors(ragAdvisor)
                .call()
                .content();

        return new RagChatResponse(reply, retrieveCitations(message));
    }

    /**
     * 응답 근거 표시용 출처 조회.
     * Advisor 가 내부적으로 사용한 검색과 동일한 파라미터로 재검색해 사용자에게 출처를 노출한다.
     */
    private List<Citation> retrieveCitations(String message) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(message)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .build());

        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(this::toCitation).toList();
    }

    private Citation toCitation(Document document) {
        Object source = document.getMetadata().get("source");
        String text = document.getText();
        String snippet = text == null ? ""
                : text.length() > SNIPPET_MAX_LENGTH ? text.substring(0, SNIPPET_MAX_LENGTH) + "…" : text;
        return new Citation(source == null ? null : source.toString(), document.getScore(), snippet);
    }
}
