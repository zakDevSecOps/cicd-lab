# Functional to Technical Workflow

This document explains how to approach feature development by moving from functional requirements through to technical implementation in phases.

## Purpose

When building features, it is easy to jump straight to code. This leads to:
- Incomplete understanding of the problem
- Missing edge cases
- Poor architectural decisions
- Rework when requirements clarify

This workflow provides a structured approach to move from "what do we need" to "how do we build it" systematically.

## The Phases

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   Functional       Design         Technical       Implementation   │
│  Requirements  ──▶  Phase    ──▶   Design    ──▶     Phase         │
│                                                                     │
│   "What"           "Why &        "How"           "Build"            │
│                    Trade-offs"                                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Functional Requirements

### Goal
Understand WHAT the feature needs to do from a user/system perspective, without thinking about implementation.

### Questions to Answer
- What problem does this solve?
- Who uses this feature?
- What are the inputs and outputs?
- What are the success criteria?
- What are the failure scenarios?

### Example: Model Warmup Readiness

**Problem**: Users cannot know if the RAG API is ready to serve requests.

**Users**: Frontend applications, health check systems, load balancers.

**Inputs**: None (status query).

**Outputs**: Ready/Not Ready indicator.

**Success Criteria**:
- Before warmup: returns "not ready"
- After warmup: returns "ready"
- Transition is atomic (no flapping)

**Failure Scenarios**:
- Warmup fails permanently (should indicate error, not perpetual "not ready")
- Warmup times out (configurable timeout, then fail)

### Output of This Phase
A clear statement of requirements that makes no assumptions about implementation.

---

## Phase 2: Design Phase

### Goal
Explore WHY certain approaches might work, understand trade-offs, and narrow down options.

### Questions to Answer
- What are the possible approaches?
- What are the trade-offs of each?
- What constraints exist (performance, complexity, dependencies)?
- What similar patterns exist in the codebase or ecosystem?

### Example: Model Warmup Readiness

**Possible Approaches**:

| Approach | Pros | Cons |
|----------|------|------|
| A: Separate readiness endpoint | Clear separation, standard practice | Another endpoint to maintain |
| B: Block requests until ready | Simple mental model | Requests queue, may timeout |
| C: Return 503 from all endpoints | No new endpoint | Requires filter, affects all paths |
| D: Health endpoint with readiness | Uses existing infra | Mixes liveness and readiness |

**Constraints**:
- Docker health check already uses `/actuator/health`
- Kubernetes distinguishes liveness vs readiness probes
- Frontend needs clear signal for user feedback

**Similar Patterns**:
- Spring Boot's `ReadinessStateHealthIndicator`
- ApplicationAvailability abstraction
- Custom health indicators

**Selected Approach**: Combine A (readiness endpoint) with Spring Boot's availability system for proper integration.

### Output of This Phase
A chosen approach with clear rationale.

---

## Phase 3: Technical Design

### Goal
Define HOW to implement the chosen approach at a technical level.

### Questions to Answer
- What components are involved?
- What is the data flow?
- What is the initialization sequence?
- What are the dependencies?
- What are the interfaces?

### Example: Model Warmup Readiness

**Components**:
1. `WarmupState` - Holds current warmup status
2. `ReadinessController` - Exposes `/api/ready` endpoint
3. `VectorStoreConfig.embeddingWarmupRunner` - Updates state on completion
4. `ReadinessHealthIndicator` - Integrates with actuator

**Data Flow**:
```
Application Start
       │
       └─▶ WarmupState created (status = WARMING_UP)
                 │
                 └─▶ ApplicationRunner executes
                           │
                           ├─▶ Success: WarmupState.markReady()
                           │
                           └─▶ Failure: WarmupState.markFailed(reason)
                                     │
                                     └─▶ /api/ready returns status
```

**Initialization Sequence**:
```java
// Bean creation order
1. WarmupState @Component  (status = WARMING_UP)
2. EmbeddingModel @Bean
3. VectorStore @Bean @Lazy
4. ApplicationRunner @Bean  (depends on EmbeddingModel, WarmupState)
```

**Interface**:
```java
public interface WarmupState {
    enum Status { WARMING_UP, READY, FAILED }

    Status getStatus();
    String getFailureReason();
    void markReady();
    void markFailed(String reason);
}
```

**API Response**:
```json
// GET /api/ready
// HTTP 200 when ready, 503 when not

{
  "status": "READY",          // or "WARMING_UP" or "FAILED"
  "failureReason": null,      // or error message
  "warmupDurationMs": 12345   // how long warmup took
}
```

### Output of This Phase
A technical specification that can be implemented.

---

## Phase 4: Implementation

### Goal
BUILD the feature following the technical design.

### Steps
1. Create failing test
2. Implement component
3. Pass test
4. Integrate with existing code
5. Run full build
6. Test in Docker
7. Update documentation

### Example: Model Warmup Readiness

**Step 1: Create test**
```java
@Test
void shouldReturnNotReadyBeforeWarmup() {
    WarmupState state = new WarmupStateImpl();
    assertEquals(Status.WARMING_UP, state.getStatus());
}

@Test
void shouldReturnReadyAfterMarkReady() {
    WarmupState state = new WarmupStateImpl();
    state.markReady();
    assertEquals(Status.READY, state.getStatus());
}
```

**Step 2-3: Implement and pass**
```java
@Component
public class WarmupStateImpl implements WarmupState {
    private volatile Status status = Status.WARMING_UP;
    private volatile String failureReason;
    private volatile long warmupDurationMs;
    private final long startTime = System.currentTimeMillis();

    @Override
    public void markReady() {
        this.warmupDurationMs = System.currentTimeMillis() - startTime;
        this.status = Status.READY;
    }

    @Override
    public void markFailed(String reason) {
        this.warmupDurationMs = System.currentTimeMillis() - startTime;
        this.failureReason = reason;
        this.status = Status.FAILED;
    }
    // ... getters
}
```

**Step 4: Integrate**
```java
// In VectorStoreConfig
@Bean
public ApplicationRunner embeddingWarmupRunner(
        EmbeddingModel embeddingModel,
        WarmupState warmupState) {
    return args -> {
        try {
            embeddingModel.embed("warmup test");
            warmupState.markReady();
        } catch (Exception e) {
            warmupState.markFailed(e.getMessage());
        }
    };
}
```

**Step 5-6: Build and test**
```bash
mvn clean package
docker compose build --no-cache rag-api
docker compose up -d
curl http://localhost:8080/api/ready
```

**Step 7: Document**
- Update `CONTEXT.md` with new component
- Update `milestones.md` with completion
- Update `debug-workflow.md` if issue resolved

---

## Applying to RAG Application

### Current Feature: Warmup Readiness

| Phase | Status |
|-------|--------|
| 1. Functional Requirements | Complete |
| 2. Design Phase | Complete (approach selected) |
| 3. Technical Design | In Progress |
| 4. Implementation | Not Started |

### Future Features

When adding features like "LLM Integration for Answer Generation":

**Phase 1**: What does it mean to generate answers? What model? What context window? What happens when context is too large?

**Phase 2**: OpenAI vs local model? Streaming vs batch? Context compression strategies?

**Phase 3**: Component design, API contract, error handling, token limits.

**Phase 4**: Build, test, document.

---

## Checklist for Each Feature

### Phase 1 Checklist
- [ ] Problem statement written
- [ ] Users identified
- [ ] Inputs/outputs defined
- [ ] Success criteria clear
- [ ] Failure scenarios listed

### Phase 2 Checklist
- [ ] Multiple approaches considered
- [ ] Trade-offs documented
- [ ] Constraints identified
- [ ] Approach selected with rationale

### Phase 3 Checklist
- [ ] Components identified
- [ ] Data flow documented
- [ ] Interfaces defined
- [ ] Dependencies mapped
- [ ] Initialization sequence clear

### Phase 4 Checklist
- [ ] Tests written first
- [ ] Implementation complete
- [ ] Integration tested
- [ ] Docker tested
- [ ] Documentation updated

---

## Anti-Patterns to Avoid

### Jumping to Code
Starting implementation without understanding requirements leads to rework.

### Over-Engineering in Design
Keep design phases focused on the current feature, not hypothetical future needs.

### Skipping Integration Testing
Unit tests pass but the feature fails in Docker due to timing, networking, or configuration.

### Forgetting Documentation
The next debugging session will waste time rediscovering what was already known.

---

## Connection to Other Docs

- **CONTEXT.md**: Overall project state, updated when architecture changes
- **milestones.md**: Track which phase each feature is in
- **debug-workflow.md**: Capture issues discovered during implementation
- **build-workflow.md**: How to validate across environments
- **observability.md**: Monitoring the feature in production
