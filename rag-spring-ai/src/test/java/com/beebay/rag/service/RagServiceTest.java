package com.beebay.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RagServiceTest {

    @Test
    void ingestFileCompletesForTailShorterThanOverlap() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<Integer> batchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return null;
        }).when(vectorStore).add(anyList());

        RagService ragService = new RagService(vectorStore);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "loop.txt",
            "text/plain",
            repeatedContent(550).getBytes(StandardCharsets.UTF_8)
        );

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            int chunks = ragService.ingestFile(file);
            assertEquals(2, chunks);
        });

        verify(vectorStore, times(1)).add(anyList());
        assertEquals(List.of(2), batchSizes);
    }

    private static String repeatedContent(int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append("abcdefghijklmnopqrstuvwxyz ");
        }
        return builder.substring(0, length);
    }
}
