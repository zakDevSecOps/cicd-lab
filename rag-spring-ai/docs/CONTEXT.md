# Project Context

## Overview

This is a RAG (Retrieval-Augmented Generation) application built with:
- **Backend**: Spring Boot 3.3.0 + Spring AI 1.0.0-M1
- **Vector Store**: ChromaDB
- **Embeddings**: TransformersEmbeddingModel (MiniLM-L6-v2, local execution)
- **Frontend**: Next.js
- **Monitoring**: Prometheus + Grafana

## Current Architecture

```
┌─────────────┐     ┌─────────────────────────────────────┐     ┌─────────────┐
│  Next.js UI │────▶│           Spring AI API             │────▶│  ChromaDB   │
│    :3000    │     │              :8080                  │     │   :8000     │
└─────────────┘     └─────────────────────────────────────┘     └─────────────┘
                           │              │
                    ┌──────┴──────┐ ┌─────┴─────┐
                    │  MiniLM-L6  │ │ Prometheus│
                    │  Embeddings │ │  Grafana  │
                    └─────────────┘ └───────────┘
```

## Key Components

### VectorStoreConfig (config/VectorStoreConfig.java)

Defines the bean wiring for vector store infrastructure:
- `EmbeddingModel`: TransformersEmbeddingModel (MiniLM-L6-v2)
- `ChromaApi`: REST client to ChromaDB
- `VectorStore`: ChromaVectorStore (marked `@Lazy`)
- `embeddingWarmupRunner`: ApplicationRunner that pre-loads the embedding model

### RagService (service/RagService.java)

Core RAG logic:
- `ingest(String content)`: Chunks text and stores in vector store
- `ingestFile(MultipartFile file)`: File upload + chunking + batched ingestion
- `query(String question)`: Similarity search in vector store
- Chunk size: 500 chars, overlap: 100 chars, batch size: 5

### RagController (controller/RagController.java)

REST endpoints:
- `POST /api/rag/query` - Query documents
- `POST /api/rag/ingest` - Ingest text content
- `POST /api/rag/upload` - Upload file for ingestion
- `GET /api/rag/health` - Health check

## Configuration Variables That Affect Behavior

### Virtual Threads

Location: `application.yml`
```yaml
spring:
  threads:
    virtual:
      enabled: true  # Currently enabled
```

Impact:
- When ON: Uses virtual threads for request handling, may interact poorly with native code (ONNX runtime)
- When OFF: Uses platform threads, more predictable but less scalable

### JVM Memory

Location: `docker-compose.yml`
```yaml
environment:
  - JAVA_OPTS=-Xms2g -Xmx6g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

Impact:
- High memory (current): Allows lazy loading, may mask timing issues
- Low memory: Forces earlier GC, surfaces timing bugs faster

### Model Warmup

Location: `VectorStoreConfig.java:45-57`
- ApplicationRunner executes async during startup
- Does not block HTTP endpoint availability

## Known Issues

### 1. Model Warmup Race Condition

**Problem**: Requests sent before the embedding model fully warms up may fail or block.

**Root Cause**: The application uses:
- `@Lazy VectorStore` to defer initialization
- `ApplicationRunner` for warmup, but this doesn't block HTTP endpoints
- If a request arrives during warmup, it triggers VectorStore initialization which depends on the EmbeddingModel

**Current Mitigation**:
- 90-second `start_period` in Docker healthcheck
- Warmup runner logs completion status

**Potential Solutions**:
- Add readiness probe that checks warmup completion
- Use a warmup state flag that endpoints check
- Block requests until warmup completes using a latch

**Variable Isolation Required**:
The issue may be caused by interaction of multiple factors:
1. Virtual threads + ONNX native code → thread pinning
2. High memory → lazy loading masks timing bugs
3. Warmup timing → async ApplicationRunner doesn't block endpoints

To isolate the root cause, test with:
- Virtual threads OFF + high memory (isolate VT)
- Virtual threads ON + low memory (isolate memory masking)
- Both OFF/low (baseline)

See `docs/debug-workflow.md` → "Variable Isolation Debugging" for full procedure.

### 2. Component Dependency Chain

The initialization order matters:

```
1. EmbeddingModel bean created (TransformersEmbeddingModel)
2. Model download triggered (~90MB, first run only)
3. Model loaded into memory (ONNX runtime)
4. ApplicationRunner warmup executes
5. ChromaApi bean created
6. VectorStore bean created (lazy, on first use)
```

### 3. Memory Pressure During Ingestion

Large file uploads can cause memory issues:
- File read into memory
- Chunking creates many Document objects
- Embedding generation is CPU/memory intensive
- Batching (5 chunks) with GC hints helps

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CHROMA_URL` | http://localhost:8000 | ChromaDB connection |
| `CHROMA_COLLECTION` | rag-documents | Collection name |
| `SERVER_PORT` | 8080 | API port |
| `CORS_ORIGINS` | http://localhost:3000 | Allowed CORS origins |

## Docker Settings

From `docker-compose.yml`:
- Memory limit: 8GB
- JVM: `-Xms2g -Xmx6g -XX:+UseG1GC`
- Health check start period: 90s (for warmup)

## Development Workflow

See:
- `docs/build-workflow.md` - Environment progression
- `docs/debug-workflow.md` - Issue tracking
- `docs/milestones.md` - Project phases
- `docs/functional-to-technical-workflow.md` - Feature development approach
- `docs/observability.md` - Monitoring setup

## Current Focus

Understanding and resolving the warmup timing issue:
- Why requests fail before model loads
- How to properly sequence dependencies
- How to expose readiness state to clients
