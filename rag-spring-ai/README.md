# RAG Application with Spring AI

A Retrieval-Augmented Generation (RAG) application using Spring AI, ChromaDB, and sentence-transformers embeddings with a Next.js frontend.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Next.js UI │────▶│  Spring AI  │────▶│  ChromaDB   │
│   :3000     │     │    :8080    │     │    :8000    │
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                    ┌──────┴──────┐
                    │  MiniLM-L6  │
                    │  Embeddings │
                    └─────────────┘
```

| Component | Description |
|-----------|-------------|
| **rag-spring-ai** | Spring Boot backend with RAG endpoints |
| **rag-next-ui** | Next.js frontend for querying |
| **ChromaDB** | Vector database for document storage |
| **MiniLM-L6-v2** | Local embedding model (sentence-transformers) |

## Prerequisites

- Docker & Docker Compose
- Java 21 (for local development)
- Node.js 20 (for UI development)

## Quick Start

### Option 1: Run Everything with Docker

```bash
# From rag-spring-ai directory
docker compose up -d
```

This starts all services:
- ChromaDB at http://localhost:8000
- Spring AI API at http://localhost:8080
- Next.js UI at http://localhost:3000

### Option 2: Local Development

**1. Start ChromaDB only:**
```bash
docker compose -f docker-compose.dev.yml up -d
```

**2. Run Spring Boot API:**
```bash
./mvnw spring-boot:run
```

**3. Run Next.js UI (in separate terminal):**
```bash
cd ../rag-next-ui
npm install
npm run dev
```

## API Endpoints

### Query Documents
```bash
POST /api/rag/query
Content-Type: application/json

{
  "question": "What is the main topic?"
}
```

### Ingest Documents
```bash
POST /api/rag/ingest
Content-Type: application/json

{
  "content": "Your document text here..."
}
```

### Health Check
```bash
GET /api/rag/health
GET /actuator/health
```

## Usage Example

**1. Ingest a document:**
```bash
curl -X POST http://localhost:8080/api/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{"content": "Spring AI is a framework for building AI applications in Java. It provides integrations with various AI providers and vector stores."}'
```

**2. Query the document:**
```bash
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is Spring AI?"}'
```

## Configuration

Environment variables (set in `docker-compose.yml` or `.env`):

| Variable | Default | Description |
|----------|---------|-------------|
| `CHROMA_URL` | http://localhost:8000 | ChromaDB connection URL |
| `CHROMA_COLLECTION` | rag-documents | Collection name in ChromaDB |
| `SERVER_PORT` | 8080 | Spring Boot server port |
| `CORS_ORIGINS` | http://localhost:3000 | Allowed CORS origins |
| `RAG_API_URL` | http://localhost:8080 | API URL for Next.js UI |

## Deployment to OVH VM

### Initial Setup

**1. SSH into your OVH VM and run:**
```bash
# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Create app directory
sudo mkdir -p /opt/rag-app
sudo chown $USER:$USER /opt/rag-app

# Log out and back in for docker group
```

**2. Copy the production compose file:**
```bash
scp deploy/docker-compose.prod.yml user@your-ovh-ip:/opt/rag-app/docker-compose.yml
```

**3. Start the application:**
```bash
ssh user@your-ovh-ip
cd /opt/rag-app
docker compose up -d
```

### GitHub Actions Deployment

The CI/CD pipeline automatically deploys on push:
- `dev` branch → development environment
- `main` branch → production environment

**Required GitHub Secrets:**
| Secret | Description |
|--------|-------------|
| `OVH_HOST` | VM IP address |
| `OVH_USER` | SSH username |
| `OVH_SSH_KEY` | Private SSH key |

## Project Structure

```
rag-spring-ai/
├── src/main/java/com/beebay/rag/
│   ├── RagApplication.java          # Main entry point
│   ├── config/
│   │   └── VectorStoreConfig.java   # ChromaDB & embeddings config
│   ├── controller/
│   │   └── RagController.java       # REST endpoints
│   └── service/
│       └── RagService.java          # RAG logic
├── src/main/resources/
│   └── application.yml              # Configuration
├── Dockerfile
├── docker-compose.yml               # Full stack
├── docker-compose.dev.yml           # Dev (ChromaDB only)
├── deploy/
│   ├── docker-compose.prod.yml      # Production
│   └── setup-ovh.sh                 # VM setup script
└── .github/workflows/
    ├── ci.yml                       # PR checks
    └── build-deploy.yml             # Build & deploy

rag-next-ui/
├── src/app/
│   ├── layout.tsx
│   ├── page.tsx
│   └── globals.css
├── Dockerfile
└── .github/workflows/
    ├── ci.yml
    └── build-deploy.yml
```

## Troubleshooting

**ChromaDB connection refused:**
```bash
# Check if ChromaDB is running
docker ps | grep chromadb
docker logs chromadb
```

**Embedding model download slow:**
The first startup downloads the MiniLM model (~90MB). Subsequent starts use the cached model.

**API not responding:**
```bash
# Check Spring Boot logs
docker logs rag-api

# Verify health endpoint
curl http://localhost:8080/actuator/health
```

## Next Steps

- [ ] Add authentication
- [ ] Implement document upload via file
- [ ] Add streaming responses
- [ ] Configure external LLM for answer generation
- [ ] Add Nginx reverse proxy with SSL
