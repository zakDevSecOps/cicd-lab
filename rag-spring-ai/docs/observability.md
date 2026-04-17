# Observability & Monitoring

This document describes the monitoring and observability setup for the RAG application.

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Next.js   │────▶│  Spring AI  │────▶│  ChromaDB   │
│     UI      │     │     API     │     │             │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
                    /actuator/prometheus
                           │
                    ┌──────▼──────┐
                    │  Prometheus │
                    │   :9090     │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Grafana   │
                    │   :3001     │
                    └─────────────┘
```

## Components

| Component | Port | Purpose |
|-----------|------|---------|
| Spring Boot Actuator | 8080 | Exposes health, metrics, prometheus endpoints |
| Prometheus | 9090 | Scrapes and stores time-series metrics |
| Grafana | 3001 | Visualizes metrics with dashboards |
| UI Metrics Panel | 3000 | Real-time JVM metrics in the browser |

## Endpoints

### Spring Boot Actuator

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Application health status |
| `/actuator/prometheus` | Prometheus-formatted metrics |
| `/actuator/metrics` | All available metrics |
| `/actuator/info` | Application info |
| `/api/metrics` | Custom JVM metrics (JSON) |

**Example: Get health status**
```bash
curl http://localhost:8080/actuator/health
```

**Example: Get Prometheus metrics**
```bash
curl http://localhost:8080/actuator/prometheus
```

**Example: Get custom JVM metrics**
```bash
curl http://localhost:8080/api/metrics
```

Response:
```json
{
  "heap": {
    "usedMB": 256,
    "maxMB": 4096,
    "percentUsed": 6
  },
  "threads": {
    "live": 25,
    "daemon": 22
  },
  "uptime": 3600000
}
```

## Available Metrics

### JVM Metrics
- `jvm_memory_used_bytes` - Memory used by area (heap, non-heap)
- `jvm_memory_max_bytes` - Maximum memory available
- `jvm_threads_live_threads` - Current live threads
- `jvm_threads_daemon_threads` - Daemon threads
- `jvm_gc_pause_seconds` - GC pause duration

### HTTP Metrics
- `http_server_requests_seconds_count` - Request count
- `http_server_requests_seconds_sum` - Total request time
- `http_server_requests_seconds_max` - Max request time

### System Metrics
- `system_cpu_usage` - System CPU usage (0-1)
- `process_cpu_usage` - Process CPU usage (0-1)
- `process_uptime_seconds` - Process uptime

## Grafana Setup

### Access
- URL: http://localhost:3001
- Username: `admin`
- Password: `admin`

### Pre-configured Dashboard

The RAG API Dashboard is automatically provisioned with:
- JVM Memory Usage (heap used vs max)
- HTTP Request Rate
- HTTP Response Time
- Thread Count
- CPU Usage

### Custom Queries (PromQL)

**Memory usage percentage:**
```promql
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100
```

**Request rate per second:**
```promql
rate(http_server_requests_seconds_count[1m])
```

**Average response time:**
```promql
rate(http_server_requests_seconds_sum[1m]) / rate(http_server_requests_seconds_count[1m])
```

**95th percentile response time:**
```promql
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

## Prometheus Setup

### Configuration

Located at `monitoring/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'rag-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['rag-api:8080']
    scrape_interval: 5s
```

### Access
- URL: http://localhost:9090
- Targets: http://localhost:9090/targets
- Graph: http://localhost:9090/graph

## UI Metrics Panel

The Next.js UI includes a built-in metrics panel:

1. Click **"Show Metrics"** button in the header
2. View real-time:
   - Heap memory usage with progress bar
   - Thread count (live/daemon)
   - Application uptime
3. Links to Grafana and Prometheus

The panel polls `/api/metrics` every 2 seconds when visible.

## Alerting (Optional)

To add alerts, create `monitoring/prometheus-alerts.yml`:

```yaml
groups:
  - name: rag-alerts
    rules:
      - alert: HighMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High heap memory usage"

      - alert: HighResponseTime
        expr: rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m]) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High average response time"
```

## Troubleshooting

### Prometheus not scraping

Check targets at http://localhost:9090/targets

Common issues:
- Container network: ensure `rag-api` is reachable from `prometheus`
- Endpoint: verify `/actuator/prometheus` returns metrics

### Grafana datasource not working

1. Go to Configuration > Data Sources
2. Click Prometheus
3. Test connection
4. Ensure URL is `http://prometheus:9090`

### High memory usage

1. Check heap dump: `jcmd <pid> GC.heap_dump /tmp/heap.hprof`
2. Analyze with VisualVM or Eclipse MAT
3. Consider increasing `-Xmx` in `JAVA_OPTS`

## Docker Commands

```bash
# View all services status
docker compose ps

# View API logs
docker logs -f rag-api

# View Prometheus logs
docker logs -f prometheus

# Restart monitoring stack
docker compose restart prometheus grafana
```
