# Build Workflow Across Environments

## Purpose

This document explains how to build and move the application through environments in a controlled way, from local development on the current machine to packaged artifacts, containers, and deployed runtime environments.

## Core Idea

The workflow should move through these stages in order:

1. Develop and verify locally
2. Package the application into deployable artifacts
3. Containerize the application for environment consistency
4. Run the full stack in a local containerized environment
5. Promote the same container image to higher environments
6. Validate behavior in each environment before moving forward

The key principle is simple:
- build once
- validate at each stage
- minimize environment-specific differences

## Environment Ladder

### 1. Local Development Environment

Purpose:
- write code quickly
- run focused tests
- debug application logic

Typical characteristics:
- source code mounted directly on the machine
- local IDE and terminal
- fast iteration
- partial dependencies may run locally or in Docker

For this project:
- Spring Boot code is edited and tested here
- focused Maven tests run here
- debugging of chunking, warmup, and upload flow starts here

Typical actions:

```powershell
mvn test
mvn -Dtest=RagServiceTest test
```

What should be validated here:
- code compiles
- unit and focused tests pass
- obvious logic bugs are fixed before packaging

### 2. Local Packaged Artifact Environment

Purpose:
- verify the application can be built as a deployable artifact
- catch packaging and startup issues early

For a Spring Boot app, the artifact is typically:
- an executable JAR

Typical actions:

```powershell
mvn clean package
java -jar target/rag-spring-ai-0.1.0-SNAPSHOT.jar
```

What should be validated here:
- packaging succeeds
- the app starts outside the IDE
- configuration is loaded correctly
- startup lifecycle issues are caught

Why this matters:
- some failures only show up after packaging
- it separates code problems from container/runtime problems

### 3. Local Container Build Environment

Purpose:
- verify the application can be built into a container image
- ensure runtime dependencies are captured correctly

For this project:
- the API is built with the repo `Dockerfile`
- the UI has its own image and workflow

Typical actions:

```powershell
docker build -t rag-api:local .
docker run --rm -p 8080:8080 rag-api:local
```

What should be validated here:
- Docker build succeeds
- container starts successfully
- environment variables are passed correctly
- no missing files or runtime dependency issues exist

Why this matters:
- local machine behavior is not the same as container behavior
- packaging success does not guarantee container success

### 4. Local Integrated Stack Environment

Purpose:
- validate the application together with its dependencies
- test realistic service-to-service interactions

For this project, the integrated stack includes:
- `rag-api`
- `rag-ui`
- `chromadb`
- `prometheus`
- `grafana`

Typical actions:

```powershell
docker compose build --no-cache rag-api
docker compose up -d
docker compose ps
docker logs -f rag-api
```

What should be validated here:
- API can reach ChromaDB
- monitoring is scraping correctly
- upload and query flows work end-to-end
- health and metrics endpoints remain responsive

This is the first environment where:
- network behavior matters
- startup ordering matters
- memory limits and container settings matter

### 5. Shared Dev or Test Environment

Purpose:
- test the same containerized application in a more stable shared environment
- validate behavior closer to deployment conditions

Typical characteristics:
- runs on a VM or shared Docker host
- uses environment-specific configuration
- may be used by more than one person

What should be validated here:
- container images pull and run correctly
- external access works
- resource sizing is adequate
- logs and dashboards are available

### 6. Production Environment

Purpose:
- run the validated release for real users or real workloads

Typical characteristics:
- immutable images
- explicit config and secrets management
- monitoring and alerting required
- rollback path required

What should be validated here:
- deployment is repeatable
- health checks pass
- startup warmup completes
- ingest and query flows work under expected load
- observability is active before traffic is trusted

## Recommended Build Progression

Follow this order for any meaningful change:

### Step 1 - Change Code Locally

Examples:
- fix chunking logic
- change warmup behavior
- adjust memory settings

### Step 2 - Run Focused Verification

Examples:

```powershell
mvn -Dtest=RagServiceTest test
```

Goal:
- verify the exact bug or behavior you changed

### Step 3 - Run Broader Build Verification

Examples:

```powershell
mvn clean package
```

Goal:
- verify the app still builds as a deployable artifact

### Step 4 - Build the Container

Examples:

```powershell
docker build -t rag-api:local .
```

Goal:
- verify packaging assumptions still hold in container form

### Step 5 - Run the Full Stack

Examples:

```powershell
docker compose build --no-cache rag-api
docker compose up -d
```

Goal:
- validate the app with Chroma, UI, and monitoring together

### Step 6 - Perform End-to-End Checks

Examples:
- upload a file
- run a query
- check `/api/rag/health`
- check `/api/metrics`
- check Prometheus and Grafana

### Step 7 - Record Results

Update:
- `docs/debug-workflow.md`
- `docs/milestones.md`
- `CONTEXT.md`

This is important because:
- debugging without written state causes repeated work
- the next iteration should start from facts, not memory

## Build Outputs By Stage

### Source Stage

Output:
- changed code only

Used for:
- local debugging

### Package Stage

Output:
- Spring Boot JAR in `target/`

Used for:
- application startup verification
- artifact-based testing

### Container Stage

Output:
- Docker image

Used for:
- consistent runtime across machines and environments

### Compose Stage

Output:
- integrated running stack

Used for:
- end-to-end system validation

### Deployment Stage

Output:
- promoted image in remote environment

Used for:
- shared validation or production use

## Environment Differences To Expect

### Local machine

Strengths:
- fastest feedback
- easiest debugging

Weaknesses:
- may hide deployment/runtime issues

### Packaged JAR

Strengths:
- exposes packaging and startup issues

Weaknesses:
- still not the full deployment environment

### Container

Strengths:
- reproducible runtime
- closer to real deployment

Weaknesses:
- adds image build and runtime configuration complexity

### Docker Compose stack

Strengths:
- realistic integration testing

Weaknesses:
- failures may come from networking, startup order, or resource limits

### Remote environment

Strengths:
- closest to reality

Weaknesses:
- slower feedback
- more operational complexity

## How To Think About Promotion

Do not jump directly from code change to production deployment.

Use this promotion model:

1. Prove the code change locally
2. Prove the package can be built
3. Prove the container starts
4. Prove the integrated stack works
5. Promote the same validated image upward

The more environments share the same image and config model, the fewer surprises you get.

## Practical Workflow For This Repo

When changing backend logic:

```powershell
mvn -Dtest=RagServiceTest test
mvn clean package
docker compose build --no-cache rag-api
docker compose up -d
docker logs -f rag-api
```

Then validate:

```powershell
curl http://localhost:8080/api/rag/health
curl http://localhost:8080/api/metrics
```

Then run a real upload and query test from the UI or API.

When changing observability:
- verify API metrics locally
- verify `/actuator/prometheus`
- verify Prometheus target health
- verify Grafana dashboard data

When changing deployment settings:
- verify Compose first
- then verify the remote deployment path

## Release Readiness Checklist

- [ ] Code change implemented
- [ ] Focused tests passed
- [ ] Full package build passed
- [ ] Container build passed
- [ ] Compose stack started successfully
- [ ] Health endpoint verified
- [ ] Metrics endpoint verified
- [ ] Real upload tested
- [ ] Real query tested
- [ ] Logs reviewed
- [ ] Docs updated

## Documentation Links

- `CONTEXT.md`
- `docs/milestones.md`
- `docs/debug-workflow.md`
- `docs/observability.md`
