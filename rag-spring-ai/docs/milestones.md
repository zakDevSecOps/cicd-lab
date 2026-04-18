# Project Milestones

## Phase 1: Foundation [COMPLETE]

### 1.1 Project Setup
- [x] Initialize Spring Boot 3.3.0 project
- [x] Add Spring AI dependencies (spring-ai-core, spring-ai-chroma-store, spring-ai-transformers)
- [x] Configure Maven with Spring AI BOM
- [x] Set up basic application.yml

### 1.2 ChromaDB Integration
- [x] Add ChromaVectorStore configuration
- [x] Configure collection name and URL via environment variables
- [x] Test ChromaDB connectivity

### 1.3 Embedding Model Setup
- [x] Configure TransformersEmbeddingModel (MiniLM-L6-v2)
- [x] Implement warmup runner for model pre-loading
- [x] Verify embedding generation works

## Phase 2: Core RAG Features [COMPLETE]

### 2.1 Document Ingestion
- [x] Implement text content ingestion endpoint
- [x] Add document chunking logic (500 char chunks, 100 char overlap)
- [x] Store chunks in ChromaDB with metadata

### 2.2 File Upload
- [x] Add file upload endpoint
- [x] Implement file parsing to text
- [x] Add batched ingestion (batch size: 5)
- [x] Add memory logging for debugging

### 2.3 Query Functionality
- [x] Implement similarity search endpoint
- [x] Configure top-K results (5)
- [x] Format and return results

## Phase 3: Infrastructure [COMPLETE]

### 3.1 Docker Setup
- [x] Create Dockerfile for Spring Boot app
- [x] Create docker-compose.yml with full stack
- [x] Create docker-compose.dev.yml for local development
- [x] Configure health checks with appropriate timeouts

### 3.2 Memory Configuration
- [x] Set JVM options (-Xms2g -Xmx6g -XX:+UseG1GC)
- [x] Set container memory limit (8GB)
- [x] Add GC hints between batches

### 3.3 Frontend Integration
- [x] Create Next.js UI project structure
- [x] Connect UI to Spring AI backend
- [x] Configure CORS settings

## Phase 4: Observability [COMPLETE]

### 4.1 Actuator Setup
- [x] Enable actuator endpoints (health, metrics, prometheus)
- [x] Expose custom metrics endpoint
- [x] Configure health check details

### 4.2 Prometheus Integration
- [x] Create prometheus.yml configuration
- [x] Add Prometheus to docker-compose
- [x] Configure scrape targets

### 4.3 Grafana Dashboard
- [x] Add Grafana to docker-compose
- [x] Create provisioning configuration
- [x] Build RAG API dashboard (JVM memory, HTTP requests, threads, CPU)

### 4.4 UI Metrics Panel
- [x] Add metrics display in Next.js UI
- [x] Poll /api/metrics endpoint
- [x] Link to Grafana/Prometheus

## Phase 5: Deployment [COMPLETE]

### 5.1 CI/CD Pipeline
- [x] Create ci.yml for PR checks
- [x] Create build-deploy.yml for deployments
- [x] Configure GitHub secrets

### 5.2 Production Setup
- [x] Create docker-compose.prod.yml
- [x] Create OVH VM setup script
- [x] Document deployment process

## Phase 6: Reliability [IN PROGRESS]

### 6.1 Warmup & Readiness
- [ ] **CURRENT ISSUE**: Requests fail before model warmup completes
- [ ] Implement proper readiness probe
- [ ] Add warmup completion flag
- [ ] Consider blocking requests during warmup

### 6.2 Dependency Management
- [ ] Document component dependency chain
- [ ] Ensure proper initialization order
- [ ] Add startup validation

### 6.3 Error Handling
- [ ] Add graceful error responses during warmup
- [ ] Implement retry logic for transient failures
- [ ] Add circuit breaker for ChromaDB connection

## Phase 7: Future Enhancements [PLANNED]

### 7.1 LLM Integration
- [ ] Add external LLM for answer generation
- [ ] Implement RAG pipeline with context + question + LLM
- [ ] Add streaming responses

### 7.2 Security
- [ ] Add authentication
- [ ] Configure Nginx reverse proxy
- [ ] Enable SSL/TLS

### 7.3 Advanced Features
- [ ] Multiple file format support (PDF, DOCX)
- [ ] Document metadata filtering
- [ ] Conversation history

## Milestone Summary

| Phase | Status | Key Deliverable |
|-------|--------|-----------------|
| 1. Foundation | Complete | Spring AI + ChromaDB + Embeddings |
| 2. Core RAG | Complete | Ingest + Query endpoints |
| 3. Infrastructure | Complete | Docker + Memory tuning |
| 4. Observability | Complete | Prometheus + Grafana |
| 5. Deployment | Complete | CI/CD + OVH setup |
| 6. Reliability | In Progress | Warmup race condition fix |
| 7. Enhancements | Planned | LLM + Auth + Advanced features |

## Current Blocker

**Issue**: Model warmup timing causes failures when requests arrive before the embedding model is fully loaded.

**Investigation Areas**:
1. ApplicationRunner vs bean initialization order
2. @Lazy annotation behavior with concurrent requests
3. Health check vs readiness probe distinction
4. State management for warmup completion
