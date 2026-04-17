# RAG Next.js UI

Frontend for the RAG Application built with Next.js.

## Prerequisites

- Node.js 20+
- RAG API running at http://localhost:8080

## Development

```bash
npm install
npm run dev
```

Open http://localhost:3000

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `RAG_API_URL` | http://localhost:8080 | Backend API URL |

## Build

```bash
npm run build
npm start
```

## Docker

```bash
docker build -t rag-next-ui .
docker run -p 3000:3000 -e RAG_API_URL=http://api:8080 rag-next-ui
```

## Full Stack

See [rag-spring-ai/README.md](../rag-spring-ai/README.md) for running the complete application.
