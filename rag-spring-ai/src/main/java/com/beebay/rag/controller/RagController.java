package com.beebay.rag.controller;

import com.beebay.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "${app.cors.allowed-origins:*}")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String answer = ragService.query(question);
        return Map.of(
            "question", question,
            "answer", answer
        );
    }

    @PostMapping("/ingest")
    public Map<String, String> ingest(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        ragService.ingest(content);
        return Map.of("status", "Document ingested successfully");
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("=== UPLOAD REQUEST RECEIVED === file: {}, size: {} bytes, contentType: {}",
                 file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            log.warn("Upload rejected: file is empty");
            return Map.of("status", "error", "message", "File is empty");
        }

        long startTime = System.currentTimeMillis();
        try {
            int chunks = ragService.ingestFile(file);
            long duration = System.currentTimeMillis() - startTime;
            log.info("=== UPLOAD COMPLETE === file: {}, chunks: {}, duration: {}ms",
                     file.getOriginalFilename(), chunks, duration);
            return Map.of(
                "status", "success",
                "filename", file.getOriginalFilename(),
                "chunks", chunks,
                "message", "File ingested successfully into " + chunks + " chunks"
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("=== UPLOAD FAILED === file: {}, duration: {}ms, error: {}",
                      file.getOriginalFilename(), duration, e.getMessage(), e);
            throw e;
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
