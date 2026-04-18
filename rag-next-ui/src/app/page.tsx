"use client";

import { useState, useRef, useEffect } from "react";

const API_URL = process.env.RAG_API_URL || "http://localhost:8080";

interface Metrics {
  heap: { usedMB: number; maxMB: number; percentUsed: number };
  threads: { live: number; daemon: number };
  uptime: number;
}

interface FileDetails {
  name: string;
  size: number;
  type: string;
  lastModified: number;
}

interface ClientLogEntry {
  timestamp: string;
  message: string;
}

export default function Home() {
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState("");
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState("");
  const [selectedFile, setSelectedFile] = useState<string>("");
  const [selectedFileDetails, setSelectedFileDetails] = useState<FileDetails | null>(null);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [showMetrics, setShowMetrics] = useState(false);
  const [clientLogs, setClientLogs] = useState<ClientLogEntry[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const appendClientLog = (message: string) => {
    const entry = {
      timestamp: new Date().toLocaleTimeString(),
      message,
    };
    console.info(`[RAG UI] ${message}`);
    setClientLogs((current) => [entry, ...current].slice(0, 8));
  };

  useEffect(() => {
    if (!showMetrics) return;

    const fetchMetrics = async () => {
      try {
        appendClientLog("Fetching backend metrics");
        const res = await fetch(`${API_URL}/api/metrics`);
        const data = await res.json();
        setMetrics(data);
      } catch (err) {
        console.error("Failed to fetch metrics", err);
        appendClientLog("Metrics fetch failed");
      }
    };

    fetchMetrics();
    const interval = setInterval(fetchMetrics, 2000);
    return () => clearInterval(interval);
  }, [showMetrics]);

  const handleQuery = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;

    setLoading(true);
    const startTime = performance.now();
    const traceId = crypto.randomUUID().slice(0, 8);

    appendClientLog(`[${traceId}] QUERY START: "${question.trim().slice(0, 50)}..."`);
    console.log(`[TRACE ${traceId}] Query request:`, { question, endpoint: `${API_URL}/api/rag/query` });

    try {
      const res = await fetch(`${API_URL}/api/rag/query`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question }),
      });

      const elapsed = (performance.now() - startTime).toFixed(0);
      const data = await res.json();

      console.log(`[TRACE ${traceId}] Query response:`, {
        status: res.status,
        elapsed: `${elapsed}ms`,
        answerLength: data.answer?.length || 0
      });

      if (res.ok) {
        setAnswer(data.answer);
        appendClientLog(`[${traceId}] QUERY OK: ${elapsed}ms, ${data.answer?.length || 0} chars`);
      } else {
        setAnswer(`Error: ${data.error || data.message || 'Unknown error'}`);
        appendClientLog(`[${traceId}] QUERY ERROR: HTTP ${res.status} in ${elapsed}ms`);
      }
    } catch (err) {
      const elapsed = (performance.now() - startTime).toFixed(0);
      const errorMsg = err instanceof Error ? err.message : String(err);

      console.error(`[TRACE ${traceId}] Query failed:`, { elapsed: `${elapsed}ms`, error: errorMsg });
      setAnswer("Error connecting to RAG service");
      appendClientLog(`[${traceId}] QUERY FAILED: ${errorMsg} (${elapsed}ms)`);
    } finally {
      setLoading(false);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    setSelectedFile(file ? file.name : "");
    setSelectedFileDetails(
      file
        ? {
            name: file.name,
            size: file.size,
            type: file.type || "unknown",
            lastModified: file.lastModified,
          }
        : null
    );
    setUploadStatus("");
    if (file) {
      appendClientLog(`Selected file ${file.name} (${formatFileSize(file.size)})`);
    }
  };

  const handleUpload = async () => {
    const file = fileInputRef.current?.files?.[0];
    if (!file) {
      setUploadStatus("Please select a file");
      appendClientLog("Upload blocked: no file selected");
      return;
    }

    setUploading(true);
    setUploadStatus("Uploading and processing...");

    const startTime = performance.now();
    const traceId = crypto.randomUUID().slice(0, 8);

    appendClientLog(`[${traceId}] UPLOAD START: ${file.name} (${formatFileSize(file.size)})`);
    console.log(`[TRACE ${traceId}] Upload request:`, {
      filename: file.name,
      size: file.size,
      type: file.type,
      endpoint: `${API_URL}/api/rag/upload`
    });

    try {
      const formData = new FormData();
      formData.append("file", file);

      const res = await fetch(`${API_URL}/api/rag/upload`, {
        method: "POST",
        body: formData,
      });

      const elapsed = (performance.now() - startTime).toFixed(0);
      const data = await res.json();

      console.log(`[TRACE ${traceId}] Upload response:`, {
        status: res.status,
        elapsed: `${elapsed}ms`,
        chunks: data.chunks,
        response: data
      });

      if (res.ok && data.status === "success") {
        setUploadStatus(`Ingested "${data.filename}" into ${data.chunks} chunks`);
        appendClientLog(`[${traceId}] UPLOAD OK: ${data.chunks} chunks in ${elapsed}ms`);
        setSelectedFile("");
        setSelectedFileDetails(null);
        if (fileInputRef.current) fileInputRef.current.value = "";
      } else {
        const errorMsg = data.message || data.error || `HTTP ${res.status}`;
        setUploadStatus(`Error: ${errorMsg}`);
        appendClientLog(`[${traceId}] UPLOAD ERROR: ${errorMsg} (${elapsed}ms)`);
      }
    } catch (err) {
      const elapsed = (performance.now() - startTime).toFixed(0);
      const errorMsg = err instanceof Error ? err.message : String(err);

      console.error(`[TRACE ${traceId}] Upload failed:`, { elapsed: `${elapsed}ms`, error: errorMsg });
      setUploadStatus("Error connecting to RAG service");
      appendClientLog(`[${traceId}] UPLOAD FAILED: ${errorMsg} (${elapsed}ms)`);
    } finally {
      setUploading(false);
    }
  };

  const formatUptime = (ms: number) => {
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m ${seconds % 60}s`;
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  };

  return (
    <main style={{ maxWidth: "900px", margin: "0 auto", padding: "2rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1.5rem" }}>
        <h1>RAG Assistant</h1>
        <button
          onClick={() => setShowMetrics(!showMetrics)}
          style={{
            padding: "0.5rem 1rem",
            fontSize: "0.85rem",
            backgroundColor: showMetrics ? "#6c757d" : "#17a2b8",
            color: "white",
            border: "none",
            borderRadius: "4px",
            cursor: "pointer",
          }}
        >
          {showMetrics ? "Hide Metrics" : "Show Metrics"}
        </button>
      </div>

      {showMetrics && metrics && (
        <div
          style={{
            padding: "1rem",
            marginBottom: "1.5rem",
            backgroundColor: "#1a1a2e",
            borderRadius: "8px",
            color: "#eee",
            fontFamily: "monospace",
            fontSize: "0.9rem",
          }}
        >
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "1rem" }}>
            <div>
              <div style={{ color: "#888", marginBottom: "0.25rem" }}>HEAP MEMORY</div>
              <div style={{ fontSize: "1.5rem", color: metrics.heap.percentUsed > 80 ? "#ff6b6b" : "#4ecdc4" }}>
                {metrics.heap.usedMB} MB / {metrics.heap.maxMB} MB
              </div>
              <div style={{ marginTop: "0.5rem", backgroundColor: "#333", borderRadius: "4px", height: "8px", overflow: "hidden" }}>
                <div
                  style={{
                    width: `${metrics.heap.percentUsed}%`,
                    height: "100%",
                    backgroundColor: metrics.heap.percentUsed > 80 ? "#ff6b6b" : "#4ecdc4",
                    transition: "width 0.3s",
                  }}
                />
              </div>
              <div style={{ color: "#888", marginTop: "0.25rem" }}>{metrics.heap.percentUsed}% used</div>
            </div>
            <div>
              <div style={{ color: "#888", marginBottom: "0.25rem" }}>THREADS</div>
              <div style={{ fontSize: "1.5rem", color: "#a29bfe" }}>
                {metrics.threads.live} <span style={{ fontSize: "0.9rem", color: "#888" }}>live</span>
              </div>
              <div style={{ color: "#888" }}>{metrics.threads.daemon} daemon</div>
            </div>
            <div>
              <div style={{ color: "#888", marginBottom: "0.25rem" }}>UPTIME</div>
              <div style={{ fontSize: "1.5rem", color: "#ffeaa7" }}>{formatUptime(metrics.uptime)}</div>
            </div>
          </div>
          <div style={{ marginTop: "1rem", fontSize: "0.8rem", color: "#666" }}>
            Grafana: <a href="http://localhost:3001" target="_blank" style={{ color: "#74b9ff" }}>localhost:3001</a> |
            Prometheus: <a href="http://localhost:9090" target="_blank" style={{ color: "#74b9ff" }}>localhost:9090</a>
          </div>
        </div>
      )}

      <div
        style={{
          padding: "1.25rem",
          marginBottom: "2rem",
          border: "2px dashed #ccc",
          borderRadius: "8px",
          backgroundColor: "#f8f9fa",
        }}
      >
        <h3 style={{ marginBottom: "1rem", color: "#333" }}>Upload Document</h3>
        <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", flexWrap: "wrap" }}>
          <label
            style={{
              padding: "0.6rem 1.2rem",
              backgroundColor: "#fff",
              border: "1px solid #ced4da",
              borderRadius: "4px",
              cursor: "pointer",
              flex: 1,
              minWidth: "200px",
              textAlign: "center",
              color: selectedFile ? "#333" : "#6c757d",
            }}
          >
            {selectedFile || "Choose file (.txt, .md)"}
            <input
              type="file"
              ref={fileInputRef}
              accept=".txt,.md,.text"
              onChange={handleFileChange}
              style={{ display: "none" }}
            />
          </label>
          <button
            onClick={handleUpload}
            disabled={uploading || !selectedFile}
            style={{
              padding: "0.6rem 1.5rem",
              fontSize: "1rem",
              backgroundColor: uploading ? "#6c757d" : "#28a745",
              color: "white",
              border: "none",
              borderRadius: "4px",
              cursor: uploading || !selectedFile ? "not-allowed" : "pointer",
              opacity: !selectedFile ? 0.6 : 1,
            }}
          >
            {uploading ? "Processing..." : "Upload & Ingest"}
          </button>
        </div>
        {uploadStatus && (
          <p
            style={{
              marginTop: "0.75rem",
              padding: "0.5rem",
              borderRadius: "4px",
              fontSize: "0.9rem",
              backgroundColor: uploadStatus.includes("Error") ? "#f8d7da" : "#d4edda",
              color: uploadStatus.includes("Error") ? "#721c24" : "#155724",
            }}
          >
            {uploadStatus}
          </p>
        )}
        {selectedFileDetails && (
          <div
            style={{
              marginTop: "0.75rem",
              padding: "0.85rem",
              borderRadius: "6px",
              backgroundColor: "#ffffff",
              border: "1px solid #dee2e6",
              fontSize: "0.9rem",
              color: "#333",
            }}
          >
            <div style={{ fontWeight: 600, marginBottom: "0.4rem" }}>Selected file metadata</div>
            <div>Name: {selectedFileDetails.name}</div>
            <div>Size: {formatFileSize(selectedFileDetails.size)}</div>
            <div>Type: {selectedFileDetails.type}</div>
            <div>Last modified: {new Date(selectedFileDetails.lastModified).toLocaleString()}</div>
          </div>
        )}
        <div
          style={{
            marginTop: "0.75rem",
            padding: "0.85rem",
            borderRadius: "6px",
            backgroundColor: "#0f172a",
            color: "#dbe4ff",
            fontFamily: "monospace",
            fontSize: "0.82rem",
          }}
        >
          <div style={{ fontWeight: 700, marginBottom: "0.45rem" }}>Client activity log</div>
          {clientLogs.length === 0 ? (
            <div style={{ color: "#94a3b8" }}>No frontend activity yet.</div>
          ) : (
            clientLogs.map((entry, index) => (
              <div key={`${entry.timestamp}-${index}`} style={{ marginBottom: "0.25rem" }}>
                [{entry.timestamp}] {entry.message}
              </div>
            ))
          )}
        </div>
      </div>

      <form onSubmit={handleQuery} style={{ marginBottom: "2rem" }}>
        <input
          type="text"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ask a question about your documents..."
          style={{
            width: "100%",
            padding: "0.85rem",
            fontSize: "1rem",
            border: "1px solid #ced4da",
            borderRadius: "4px",
            marginBottom: "1rem",
          }}
        />
        <button
          type="submit"
          disabled={loading}
          style={{
            padding: "0.75rem 2rem",
            fontSize: "1rem",
            backgroundColor: loading ? "#6c757d" : "#0070f3",
            color: "white",
            border: "none",
            borderRadius: "4px",
            cursor: loading ? "wait" : "pointer",
          }}
        >
          {loading ? "Searching..." : "Ask"}
        </button>
      </form>

      {answer && (
        <div
          style={{
            padding: "1.25rem",
            backgroundColor: "#fff",
            border: "1px solid #dee2e6",
            borderRadius: "8px",
            whiteSpace: "pre-wrap",
          }}
        >
          <h3 style={{ marginBottom: "0.75rem", color: "#333" }}>Answer:</h3>
          <p style={{ lineHeight: 1.6 }}>{answer}</p>
        </div>
      )}
    </main>
  );
}
