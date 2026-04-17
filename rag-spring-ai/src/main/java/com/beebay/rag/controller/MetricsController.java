package com.beebay.rag.controller;

import org.springframework.web.bind.annotation.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@CrossOrigin(origins = "${app.cors.allowed-origins:*}")
public class MetricsController {

    @GetMapping
    public Map<String, Object> getMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        Runtime runtime = Runtime.getRuntime();

        return Map.of(
            "heap", Map.of(
                "used", heapUsage.getUsed(),
                "max", heapUsage.getMax(),
                "committed", heapUsage.getCommitted(),
                "usedMB", heapUsage.getUsed() / (1024 * 1024),
                "maxMB", heapUsage.getMax() / (1024 * 1024),
                "percentUsed", heapUsage.getMax() > 0
                    ? Math.round((double) heapUsage.getUsed() / heapUsage.getMax() * 100)
                    : 0
            ),
            "nonHeap", Map.of(
                "used", nonHeapUsage.getUsed(),
                "usedMB", nonHeapUsage.getUsed() / (1024 * 1024)
            ),
            "threads", Map.of(
                "live", threadBean.getThreadCount(),
                "daemon", threadBean.getDaemonThreadCount(),
                "peak", threadBean.getPeakThreadCount()
            ),
            "system", Map.of(
                "availableProcessors", runtime.availableProcessors(),
                "freeMemoryMB", runtime.freeMemory() / (1024 * 1024),
                "totalMemoryMB", runtime.totalMemory() / (1024 * 1024),
                "maxMemoryMB", runtime.maxMemory() / (1024 * 1024)
            ),
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime()
        );
    }
}
