# Debug Workflow

This document tracks issues encountered during development and their solutions.

---

## Issue Template

```
### Issue: [Short Description]
**Status**: Open / Investigating / Resolved
**Severity**: Critical / High / Medium / Low
**Component**: [Component name]

**Symptoms**:
- What was observed

**Root Cause**:
- Why it happened

**Solution**:
- How it was fixed

**Prevention**:
- How to avoid in future
```

---

## Active Issues

### Issue: Model Warmup Race Condition

**Status**: ROOT CAUSE CONFIRMED
**Severity**: High
**Component**: VectorStoreConfig, RagService, PyTorch Engine

**Symptoms**:
- File uploads fail with HTTP 500 shortly after application start
- Error: `IllegalStateException: The engine PyTorch was not able to initialize`
- Simple queries may succeed, but heavy operations (upload) fail
- Sporadic failures during ~10-15 second startup window

**Confirmed Root Cause**:
```
java.lang.IllegalStateException: The engine PyTorch was not able to initialize
    at ai.djl.engine.Engine.getEngine(Engine.java:218)
    at ai.djl.ndarray.NDManager.newBaseManager(NDManager.java:120)
    at TransformersEmbeddingModel.call(TransformersEmbeddingModel.java:280)
    at TransformersEmbeddingModel.embed(TransformersEmbeddingModel.java:232)
    at ChromaVectorStore.add(ChromaVectorStore.java:100)
    at RagService.addChunksInBatches(RagService.java:96)
```

The PyTorch engine downloads native libraries (libtorch.so, libc10.so, etc.) during warmup.
When a request thread calls `embed()` before this completes, PyTorch is not initialized → crash.

**Technical Details**:

The current initialization flow:

```
Application Start
       │
       ├─▶ EmbeddingModel bean created
       │         │
       │         └─▶ TransformersEmbeddingModel constructor runs
       │                   │
       │                   └─▶ Model download/load deferred?
       │
       ├─▶ ApplicationRunner (warmup) starts
       │         │
       │         └─▶ embeddingModel.embed("warmup test")
       │                   │
       │                   └─▶ Triggers actual model download/load
       │                              (~90MB, several seconds)
       │
       ├─▶ VectorStore bean (LAZY - not created yet)
       │
       └─▶ HTTP endpoints become available
                 │
                 └─▶ Request arrives BEFORE warmup completes
                           │
                           └─▶ @Lazy VectorStore initialized
                                     │
                                     └─▶ Needs EmbeddingModel (still loading?)
                                               │
                                               └─▶ RACE CONDITION / FAILURE
```

**Key Questions**:
1. Does TransformersEmbeddingModel download on construction or first use?
2. Does ApplicationRunner block HTTP endpoints from starting?
3. Does @Lazy protect against concurrent initialization?
4. How does Spring handle bean access during ApplicationRunner?

**Investigation Steps**:
1. Add detailed logging to track initialization sequence
2. Check Spring AI source for TransformersEmbeddingModel behavior
3. Test with artificial delay in warmup to reproduce issue
4. Examine thread states during startup

**Potential Solutions**:

**Option A: Readiness Probe**
```java
@Component
public class WarmupState {
    private volatile boolean ready = false;

    public void markReady() { ready = true; }
    public boolean isReady() { return ready; }
}

// In warmup runner:
warmupState.markReady();

// Readiness endpoint:
@GetMapping("/ready")
public ResponseEntity<?> ready() {
    return warmupState.isReady()
        ? ResponseEntity.ok().build()
        : ResponseEntity.status(503).build();
}
```

**Option B: Request Blocking**
```java
@Component
public class WarmupFilter implements Filter {
    private final CountDownLatch warmupLatch = new CountDownLatch(1);

    public void filterChain(...) {
        warmupLatch.await(60, TimeUnit.SECONDS);
        chain.doFilter(request, response);
    }

    public void signalReady() {
        warmupLatch.countDown();
    }
}
```

**Option C: Eager VectorStore**
- Remove @Lazy annotation
- Accept slower startup
- Simpler but less efficient

**Option D: Synchronized Bean Access**
- Add synchronization to EmbeddingModel access
- Handle concurrent first-use scenarios

---

## Resolved Issues

### Issue: Infinite Loop During Chunking

**Status**: Resolved
**Severity**: Critical
**Component**: RagService

**Symptoms**:
- File ingestion would hang indefinitely
- CPU usage spike
- No progress in chunk processing

**Root Cause**:
When the remaining content was shorter than CHUNK_OVERLAP (100 chars), the chunking algorithm could enter an infinite loop because `nextStart` would not advance past `start`.

**Solution**:
Added boundary check in `splitIntoChunks`:
```java
int nextStart = Math.max(end - CHUNK_OVERLAP, 0);
if (nextStart <= start) {
    nextStart = end;  // Force progress
}
start = nextStart;
```

Added test case `ingestFileCompletesForTailShorterThanOverlap`.

**Prevention**:
- Always test edge cases in chunking (small files, exact chunk boundaries)
- Add timeout assertions in tests

---

### Issue: Memory Pressure During Large File Upload

**Status**: Resolved
**Severity**: High
**Component**: RagService

**Symptoms**:
- OutOfMemoryError during file upload
- GC thrashing
- Slow response times

**Root Cause**:
- Entire file loaded into memory
- All chunks created at once
- All embeddings generated in single batch

**Solution**:
1. Implemented batched processing (BATCH_SIZE = 5)
2. Added GC hints between batches
3. Added memory logging for monitoring

```java
private static final int BATCH_SIZE = 5;

private void addChunksInBatches(List<Document> chunks) {
    for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
        List<Document> batch = chunks.subList(i, end);
        vectorStore.add(batch);

        if (i + BATCH_SIZE < chunks.size()) {
            System.gc();  // Hint to GC
        }
    }
}
```

**Prevention**:
- Always process large data in batches
- Monitor memory during ingestion
- Set appropriate JVM heap limits

---

### Issue: ChromaDB Connection Timeout

**Status**: Resolved
**Severity**: Medium
**Component**: VectorStoreConfig, Docker

**Symptoms**:
- API startup fails with connection refused
- ChromaDB appears healthy but API cannot connect

**Root Cause**:
Docker Compose service order issue - API starting before ChromaDB was fully ready.

**Solution**:
Added health check with condition:
```yaml
chromadb:
  healthcheck:
    test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/api/v1/heartbeat')"]
    interval: 30s
    start_period: 10s

rag-api:
  depends_on:
    chromadb:
      condition: service_healthy
```

**Prevention**:
- Always use health checks for dependencies
- Use `condition: service_healthy` not just `depends_on`

---

## Debug Commands

### Check Startup Logs
```bash
docker logs -f rag-api 2>&1 | grep -E "(WARMUP|INGEST|MEMORY)"
```

### Monitor Memory During Request
```bash
curl http://localhost:8080/api/metrics | jq '.heap'
```

### Test Warmup Timing
```bash
# Start fresh
docker compose down -v
docker compose up -d

# Immediately try query (should fail if warmup not done)
time curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "test"}'
```

### Check Thread States
```bash
docker exec rag-api jcmd 1 Thread.print
```

### Heap Dump
```bash
docker exec rag-api jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp rag-api:/tmp/heap.hprof ./heap.hprof
```

---

## Variable Isolation Debugging

When multiple factors could cause an issue, isolate each variable to identify the true root cause.

### Scenario: Startup Failure - Multiple Suspects

**Current Configuration (all enabled)**:
- Virtual threads: enabled
- JVM memory: `-Xms2g -Xmx6g`
- Model warmup: async during startup

**Suspects**:
1. Virtual threads causing thread scheduling issues
2. High memory allowing lazy behavior that hides timing issues
3. Model warmup not completing before requests arrive

### Isolation Matrix

| Test | Virtual Threads | Memory | Expected Outcome |
|------|-----------------|--------|------------------|
| Baseline (current) | ON | High (2g-6g) | Issue occurs |
| Test A | OFF | High (2g-6g) | Isolates VT impact |
| Test B | ON | Low (512m-1g) | Isolates memory impact |
| Test C | OFF | Low (512m-1g) | Minimal config baseline |

### Configuration Changes

**Test A: Disable Virtual Threads**

`application.yml`:
```yaml
spring:
  threads:
    virtual:
      enabled: false  # Was: true
```

**Test B: Reduce Memory**

`docker-compose.yml`:
```yaml
rag-api:
  environment:
    - JAVA_OPTS=-Xms512m -Xmx1g -XX:+UseG1GC  # Was: -Xms2g -Xmx6g
  deploy:
    resources:
      limits:
        memory: 2G  # Was: 8G
```

**Test C: Both disabled/reduced**

Apply both changes above.

### Test Procedure

```powershell
# 1. Apply configuration change
# 2. Rebuild and restart
docker compose down
docker compose build --no-cache rag-api
docker compose up -d

# 3. Wait for startup
sleep 30

# 4. Check warmup completion
docker logs rag-api 2>&1 | grep "WARMUP"

# 5. Send request immediately after seeing "WARMUP COMPLETE"
time curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "test"}'

# 6. Record results
# - Did it succeed?
# - How long did it take?
# - Any errors in logs?
```

### Results Log

| Test | Date | Warmup Time | First Request | Result | Notes |
|------|------|-------------|---------------|--------|-------|
| Baseline | | | | | |
| Test A (no VT) | | | | | |
| Test B (low mem) | | | | | |
| Test C (minimal) | 2026-04-18 | 7211ms | During warmup | SUCCESS | See details below |

### Test C Results (2026-04-18)

**Configuration**:
- Virtual threads: OFF
- Memory: -Xms512m -Xmx1g (container limit 2G)

**Cold Start Timeline**:
```
14:25:35.455 - RagApplication starting
14:25:37.321 - Creating TransformersEmbeddingModel
14:25:37.341 - Creating cache directory (model download starts)
14:25:53.960 - Model download complete (tokenizer.json, libtokenizers.so)
14:25:54.657 - Model loaded (input/output names set)
14:25:55.415 - Application Started (HTTP ENDPOINTS NOW AVAILABLE)
14:25:55.511 - WARMUP START (PyTorch libs download begins)
14:25:57.001 - REQUEST ARRIVED (nio-8080-exec-1) ← 1.5s into warmup
14:26:02.722 - WARMUP COMPLETE (7211ms)
```

**Key Observations**:
1. Model files are downloaded DURING EmbeddingModel bean construction (before app starts)
2. Warmup downloads PyTorch native libraries (libc10.so, libtorch_cpu.so, etc.)
3. Request arrived 5.7 seconds BEFORE warmup completed
4. Request SUCCEEDED (HTTP 200, 0.3s response time)

**Why It Worked**:
- The `TransformersEmbeddingModel` is initialized during bean construction
- Model ONNX files are already cached by the time `ApplicationRunner` runs
- Warmup just pre-heats the PyTorch engine and tests the embedding
- Concurrent access to EmbeddingModel appears to be thread-safe
- Query uses existing ChromaDB vectors + new embedding for question

**Conclusion for Test C**:
In minimal config (no VT, low memory), requests during warmup still succeed.
The warmup is an optimization, not a hard requirement.

### Test C - File Upload During Warmup (REPRODUCES ISSUE)

**Test**: Upload spring-ai-docs.txt (~5KB, ~12 chunks) during warmup

**Timeline**:
```
14:31:45 - Container cold start
14:32:05.190 - UPLOAD REQUEST ARRIVED
14:32:05.190 - ERROR: PyTorch was not able to initialize
14:32:14.893 - Still downloading libtorch.so.gz
14:32:15.871 - WARMUP COMPLETE (12041ms)
```

**Error Stack**:
```
IllegalStateException: The engine PyTorch was not able to initialize
  → Engine.getEngine()
  → NDManager.newBaseManager()
  → TransformersEmbeddingModel.embed()
  → ChromaVectorStore.add()
  → RagService.addChunksInBatches()
```

**Why Query Worked But Upload Failed**:
- Query: retrieves existing vectors + 1 new embedding (may wait/retry)
- Upload: generates 12+ embeddings immediately → triggers Engine.getEngine() → crash

**Conclusion for Test C**:
PyTorch engine initialization is not thread-safe.
Request threads cannot use EmbeddingModel until warmup runner finishes.

---

### Issue 2: OutOfMemoryError During File Upload

**Status**: CONFIRMED
**Severity**: High
**Component**: RagService, EmbeddingModel, JVM Heap

**Error**:
```
java.lang.OutOfMemoryError: Java heap space
```

**When It Occurs**:
- Uploading files (especially larger ones like spring-ai-docs.txt)
- With low memory settings (-Xms512m -Xmx1g)
- During embedding generation for multiple chunks

**Why It Happens**:

Memory consumers during file upload:
```
┌─────────────────────────────────────────────────────────┐
│                    JVM HEAP USAGE                       │
├─────────────────────────────────────────────────────────┤
│  PyTorch Runtime          ~300-500 MB                   │
│  ONNX Model (MiniLM)      ~100-200 MB                   │
│  Tokenizer                ~50-100 MB                    │
│  File content in memory   ~5-50 MB (depends on file)    │
│  Chunk objects            ~10-50 MB                     │
│  Embedding vectors        ~50-100 MB (batch)            │
│  Spring/Tomcat overhead   ~100-200 MB                   │
├─────────────────────────────────────────────────────────┤
│  TOTAL PEAK              ~700 MB - 1.2 GB               │
└─────────────────────────────────────────────────────────┘
```

With -Xmx1g, there's no headroom for GC or spikes → OOM.

**Solution**:
High memory settings are REQUIRED, not optional:
- Minimum: `-Xms1g -Xmx2g`
- Recommended: `-Xms2g -Xmx4g` (original config was fine)
- For large files: `-Xms2g -Xmx6g`

---

### Combined Issue Analysis

**Two distinct failure modes during file upload**:

| Timing | Error | Root Cause | Fix |
|--------|-------|------------|-----|
| Early (during warmup) | `PyTorch not initialized` | Race condition | Block requests until ready |
| Anytime (low memory) | `OutOfMemoryError: heap` | Insufficient heap | Use higher -Xmx |

**Original Config Was Correct**:
```yaml
# These settings are REQUIRED, not just optimization
JAVA_OPTS=-Xms2g -Xmx6g  # Needed for embeddings
memory: 8G               # Container headroom
```

**The issue is purely the warmup race condition** - memory settings should stay high.

---

## Solution: Readiness Probes (Docker & Kubernetes)

### Probes Overview

| Probe | Question | Failure Action | Use Case |
|-------|----------|----------------|----------|
| **Liveness** | "Is process alive?" | Restart container | Deadlock detection |
| **Readiness** | "Can it handle traffic?" | Stop routing traffic | Warmup, dependencies |
| **Startup** | "Has it started yet?" | Keep waiting | Slow-starting apps |

### Kubernetes Probes Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: rag-api
          ports:
            - containerPort: 8080

          # Don't restart during slow startup (up to 5 min)
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            failureThreshold: 30
            periodSeconds: 10

          # Restart if process is dead/stuck
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            periodSeconds: 10
            failureThreshold: 3

          # Don't send traffic until model is ready
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            periodSeconds: 5
            failureThreshold: 1
```

### Docker Compose (Current Limitation)

Docker Compose only has `healthcheck` (no separate readiness):

```yaml
rag-api:
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 90s  # ← Workaround: wait 90s before checking
```

**Problem**: `start_period` is a guess. If warmup takes longer, traffic still routes.

### Spring Boot Actuator Readiness Solution

Spring Boot 2.3+ has built-in liveness/readiness support.

**Step 1: Enable in application.yml**
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState
        liveness:
          include: livenessState
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

**Step 2: Signal readiness after warmup**
```java
@Component
public class WarmupReadinessIndicator {

    private final ApplicationEventPublisher publisher;

    public WarmupReadinessIndicator(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void markReady() {
        publisher.publishEvent(new AvailabilityChangeEvent<>(
            this,
            ReadinessState.ACCEPTING_TRAFFIC
        ));
    }

    public void markNotReady() {
        publisher.publishEvent(new AvailabilityChangeEvent<>(
            this,
            ReadinessState.REFUSING_TRAFFIC
        ));
    }
}
```

**Step 3: Call from warmup runner**
```java
@Bean
public ApplicationRunner embeddingWarmupRunner(
        EmbeddingModel embeddingModel,
        WarmupReadinessIndicator readinessIndicator) {
    return args -> {
        log.info("=== WARMUP START ===");
        try {
            embeddingModel.embed("warmup test");
            readinessIndicator.markReady();
            log.info("=== WARMUP COMPLETE ===");
        } catch (Exception e) {
            log.error("=== WARMUP FAILED ===", e);
            // Keep refusing traffic
        }
    };
}
```

**Endpoints**:
- `/actuator/health/liveness` → Always UP if process alive
- `/actuator/health/readiness` → DOWN until warmup complete, then UP

### Startup Sequence with Readiness

```
┌─────────────────────────────────────────────────────────────────┐
│                     STARTUP SEQUENCE                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Container starts                                            │
│       │                                                         │
│  2. Spring Boot initializes                                     │
│       │                                                         │
│  3. /actuator/health/liveness → UP (process alive)              │
│       │                                                         │
│  4. /actuator/health/readiness → DOWN (not ready)               │
│       │                                    ↑                    │
│       │                         K8s/Docker: don't route traffic │
│       │                                                         │
│  5. Warmup runner executes (PyTorch downloads)                  │
│       │                                                         │
│  6. Warmup complete → publish ReadinessState.ACCEPTING_TRAFFIC  │
│       │                                                         │
│  7. /actuator/health/readiness → UP                             │
│       │                                    ↑                    │
│       │                         K8s/Docker: route traffic now   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Solution Comparison

| Approach | Pros | Cons |
|----------|------|------|
| `start_period` (current) | Simple, no code changes | Guess-based, not accurate |
| Spring Readiness Probe | Standard, integrated | Requires code changes |
| Custom `/ready` endpoint | Full control | Manual implementation |
| Filter with Latch | Blocks requests | Requests wait vs fail |

### Recommended Approach

Use **Spring Boot Readiness Probe** because:
1. Standard pattern recognized by K8s and orchestrators
2. Integrates with existing actuator infrastructure
3. Automatic state management via `AvailabilityChangeEvent`
4. Works with both Docker health checks and K8s probes

### Interpreting Results

**If issue disappears with virtual threads OFF (Test A)**:
- Root cause: Virtual thread interaction with model loading
- Virtual threads may cause thread pinning during ONNX model initialization
- Solution: Disable VT or ensure model loads on platform thread

**If issue disappears with low memory (Test B)**:
- Root cause: Memory allows deferred/lazy behavior
- High memory hides timing issues by allowing concurrent loading
- Solution: Force eager initialization or add proper synchronization

**If issue persists in Test C (minimal config)**:
- Root cause: Model warmup timing itself
- The issue is fundamental to the initialization sequence
- Solution: Implement readiness probe or request blocking

**If issue only occurs in baseline (all enabled)**:
- Root cause: Interaction between VT + high memory + warmup
- Complex interaction between multiple factors
- Solution: May need combination of fixes

### Deep Dive: Virtual Threads + Model Loading

Virtual threads can cause issues with native code (like ONNX runtime used by TransformersEmbeddingModel):

```
Virtual Thread           Platform Thread
     │                        │
     └─▶ embed() call         │
              │               │
              └─▶ ONNX native call (PINNED)
                       │
                       └─▶ Virtual thread cannot yield
                                │
                                └─▶ Other VTs blocked waiting
```

**Test for VT pinning**:
```bash
# Enable pinning detection
JAVA_OPTS="-Djdk.tracePinnedThreads=full"

# Check logs for pinning warnings
docker logs rag-api 2>&1 | grep -i "pinned"
```

### Deep Dive: Memory Impact on Initialization

With high memory:
- JVM allocates large heap eagerly
- GC runs less frequently
- Objects stay in memory longer
- Lazy initialization has more headroom

With low memory:
- JVM under pressure from start
- GC runs more frequently
- Forces synchronous behavior
- Timing issues surface faster

**Test memory pressure**:
```bash
# Add GC logging
JAVA_OPTS="-Xlog:gc*:file=/tmp/gc.log"

# After test
docker cp rag-api:/tmp/gc.log ./gc.log
# Analyze GC frequency during warmup
```

### Recommended Isolation Sequence

1. **Start with Test C** (minimal config)
   - Establishes baseline behavior
   - If issue exists here, it's fundamental

2. **Then Test A** (add VT only)
   - If issue appears, VT is a factor
   - Check for thread pinning

3. **Then Test B** (add memory only)
   - If issue appears, memory hides timing bugs
   - Timing is root cause, memory is mask

4. **Finally baseline** (all enabled)
   - Confirms interaction behavior
   - Informs solution design

---

## Debug Checklist

When encountering a new issue:

1. **Reproduce**
   - [ ] Can you reproduce consistently?
   - [ ] What are the exact steps?
   - [ ] What environment (local/Docker/prod)?

2. **Isolate**
   - [ ] Which component is involved?
   - [ ] Is it timing-related?
   - [ ] Is it data-related?

3. **Gather Data**
   - [ ] Check logs: `docker logs rag-api`
   - [ ] Check metrics: `/api/metrics`
   - [ ] Check health: `/actuator/health`

4. **Hypothesize**
   - [ ] What could cause this behavior?
   - [ ] What changed recently?
   - [ ] Is this a known issue pattern?

5. **Test**
   - [ ] Add targeted logging
   - [ ] Create minimal reproduction
   - [ ] Write failing test case

6. **Fix & Verify**
   - [ ] Implement fix
   - [ ] Run focused tests
   - [ ] Run full build
   - [ ] Test in Docker

7. **Document**
   - [ ] Add to this file
   - [ ] Update CONTEXT.md if architecture changed
   - [ ] Update milestones.md if milestone affected
