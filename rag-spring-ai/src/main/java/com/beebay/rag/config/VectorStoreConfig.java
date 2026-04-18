package com.beebay.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.ChromaApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.ChromaVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestTemplate;

@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Value("${spring.ai.vectorstore.chroma.collection-name:rag-documents}")
    private String collectionName;

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Creating TransformersEmbeddingModel (MiniLM-L6-v2)...");
        return new TransformersEmbeddingModel();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ChromaApi chromaApi(RestTemplate restTemplate) {
        return new ChromaApi(chromaUrl, restTemplate);
    }

    @Bean
    public ApplicationRunner embeddingWarmupRunner(
            EmbeddingModel embeddingModel,
            WarmupReadinessIndicator readinessIndicator) {
        return args -> {
            log.info("=== WARMUP START === Loading embedding model...");
            long start = System.currentTimeMillis();
            try {
                var result = embeddingModel.embed("warmup test");
                log.info("=== WARMUP COMPLETE === Model loaded in {}ms, embedding dimensions: {}",
                    System.currentTimeMillis() - start, result.size());
                readinessIndicator.markReady();
            } catch (Exception e) {
                log.error("=== WARMUP FAILED === Error loading embedding model: {}", e.getMessage(), e);
                readinessIndicator.markFailed(e.getMessage());
            }
        };
    }

    @Bean
    @Lazy
    public VectorStore vectorStore(EmbeddingModel embeddingModel, ChromaApi chromaApi) {
        return new ChromaVectorStore(embeddingModel, chromaApi, collectionName, true);
    }
}
