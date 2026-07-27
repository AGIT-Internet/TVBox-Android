package com.github.tvbox.osc.server;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

final class LanDiagnostics {
    private static final int LIMIT = 100;
    private static final long STARTED_AT = System.currentTimeMillis();
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    static synchronized void record(String method, String path, int status, long duration, String error) {
        if (ENTRIES.size() >= LIMIT) ENTRIES.removeFirst();
        ENTRIES.addLast(new Entry(System.currentTimeMillis(), method, path, status, duration, error));
    }

    static synchronized JSONObject snapshot() throws Exception {
        JSONArray requests = new JSONArray();
        for (Entry entry : ENTRIES) {
            requests.put(new JSONObject()
                    .put("time", entry.time)
                    .put("method", entry.method)
                    .put("path", entry.path)
                    .put("status", entry.status)
                    .put("duration", entry.duration)
                    .put("error", entry.error));
        }
        Runtime runtime = Runtime.getRuntime();
        return new JSONObject()
                .put("startedAt", STARTED_AT)
                .put("uptime", SystemClock.elapsedRealtime())
                .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                .put("memoryMax", runtime.maxMemory())
                .put("sessions", LanServerManager.get().getSessionCount())
                .put("keepAlive", LanServerService.diagnostics())
                .put("requests", requests);
    }

    private static final class Entry {
        final long time;
        final String method;
        final String path;
        final int status;
        final long duration;
        final String error;

        Entry(long time, String method, String path, int status, long duration, String error) {
            this.time = time;
            this.method = method;
            this.path = path;
            this.status = status;
            this.duration = duration;
            this.error = error == null ? "" : error;
        }
    }

    private LanDiagnostics() {
    }
}
