package com.beebay.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;

    // Chunk size for splitting large documents (in characters)
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;
    // Process chunks in small batches to reduce memory pressure
    private static final int BATCH_SIZE = 5;

    public RagService(@Lazy VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingest(String content) {
        log.info("=== INGEST START (API) === content length: {} chars", content.length());
        logMemoryState("before chunking");

        List<Document> chunks = splitIntoChunks(content, "api");
        log.info("Chunking complete: {} chunks created", chunks.size());
        logMemoryState("after chunking");

        addChunksInBatches(chunks);
        log.info("=== INGEST COMPLETE (API) ===");
    }

    public int ingestFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("=== FILE INGEST START === file: {}, size: {} bytes", filename, fileSize);
        logMemoryState("before reading file");

        log.info("Reading file bytes...");
        long readStart = System.currentTimeMillis();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        log.info("File read complete in {}ms, content length: {} chars",
                 System.currentTimeMillis() - readStart, content.length());
        logMemoryState("after reading file");

        log.info("Starting chunking...");
        long chunkStart = System.currentTimeMillis();
        List<Document> chunks = splitIntoChunks(content, filename);
        log.info("Chunking complete in {}ms: {} chunks created",
                 System.currentTimeMillis() - chunkStart, chunks.size());
        logMemoryState("after chunking");

        log.info("Starting batch ingestion of {} chunks...", chunks.size());
        long batchStart = System.currentTimeMillis();
        addChunksInBatches(chunks);
        log.info("Batch ingestion complete in {}ms", System.currentTimeMillis() - batchStart);
        logMemoryState("after batch ingestion");

        log.info("=== FILE INGEST COMPLETE === file: {}, chunks: {}", filename, chunks.size());
        return chunks.size();
    }

    private void addChunksInBatches(List<Document> chunks) {
        int totalBatches = (int) Math.ceil((double) chunks.size() / BATCH_SIZE);
        log.info("Processing {} chunks in {} batches (batch size: {})",
                 chunks.size(), totalBatches, BATCH_SIZE);

        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            int end = Math.min(i + BATCH_SIZE, chunks.size());
            List<Document> batch = chunks.subList(i, end);

            log.info(">>> BATCH {}/{}: processing chunks {} to {} (size: {})",
                     batchNum, totalBatches, i, end - 1, batch.size());
            logMemoryState("before batch " + batchNum);

            long batchStart = System.currentTimeMillis();
            log.info("Calling vectorStore.add() for batch {}...", batchNum);

            try {
                vectorStore.add(batch);
                log.info("vectorStore.add() completed for batch {} in {}ms",
                         batchNum, System.currentTimeMillis() - batchStart);
            } catch (Exception e) {
                log.error("vectorStore.add() FAILED for batch {}: {}", batchNum, e.getMessage(), e);
                throw e;
            }

            logMemoryState("after batch " + batchNum);

            // Hint to GC between batches
            if (i + BATCH_SIZE < chunks.size()) {
                log.info("Requesting GC between batches...");
                long gcStart = System.currentTimeMillis();
                System.gc();
                log.info("GC hint complete in {}ms", System.currentTimeMillis() - gcStart);
                logMemoryState("after GC hint");
            }
        }
        log.info("All {} batches processed successfully", totalBatches);
    }

    private void logMemoryState(String phase) {
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory() / (1024 * 1024);
        long totalMem = runtime.totalMemory() / (1024 * 1024);
        long freeMem = runtime.freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;
        int threadCount = Thread.activeCount();

        log.info("MEMORY [{}]: used={}MB, free={}MB, total={}MB, max={}MB, threads={}",
                 phase, usedMem, freeMem, totalMem, maxMem, threadCount);
    }

    public String query(String question) {
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.query(question).withTopK(5)
        );

        if (results.isEmpty()) {
            return "No relevant documents found.";
        }

        return results.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<Document> splitIntoChunks(String content, String source) {
        List<Document> chunks = new ArrayList<>();

        if (content.length() <= CHUNK_SIZE) {
            chunks.add(new Document(content, Map.of("source", source, "chunk", 0)));
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;

        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());

            // Try to break at a sentence or paragraph boundary
            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf(". ", end);
                int lastNewline = content.lastIndexOf("\n", end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start + CHUNK_SIZE / 2) {
                    end = breakPoint + 1;
                }
            }

            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(new Document(chunk, Map.of(
                    "source", source,
                    "chunk", chunkIndex++
                )));
            }

            if (end == content.length()) {
                break;
            }

            int nextStart = Math.max(end - CHUNK_OVERLAP, 0);
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        return chunks;
    }
}
