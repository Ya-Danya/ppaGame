package com.example.paperfx.server;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.net.InetSocketAddress;

/**
 * UDP-backed client session.
 *
 * Each session is identified by the remote UDP address (IP:port).
 * The server is responsible for receiving datagrams and calling {@link #onDatagram(String)}.
 */
final class ClientConn implements Closeable {
    final ServerMain server;
    final InetSocketAddress addr;

    volatile long lastSeenMs = System.currentTimeMillis();

    volatile boolean authed = false;
    volatile String userId;
    volatile String username;

    volatile String roomId = "MAIN";
    volatile String playerId;
    volatile boolean spectator = false;

    volatile long lastChatMs = 0;

    // ---- persistent profile/stats cache (loaded on login) ----
    // All-time totals (from DB, plus in-memory pending deltas)
    volatile long killsTotal = 0;
    volatile long areaTotal = 0;

    // Best values (cached from DB / session)
    volatile int bestScore = 0;          // max score ever (best_score in app_users)
    volatile int bestKillsInGame = 0;    // max kills in a single game/session
    volatile int bestKillStreak = 0;     // max kill streak ever

    // Pending deltas to flush to DB (batched)
    volatile long pendingKills = 0;
    volatile long pendingArea = 0;
    volatile boolean statsDirty = false;

    // Current session (since last join as a player)
    volatile int sessionKills = 0;
    volatile int currentKillStreak = 0;
    volatile int sessionMaxKillStreak = 0;
    volatile int sessionMaxScore = 0;

    // Achievement cache (to avoid extra DB writes)
    final java.util.Set<String> unlockedAchievements = java.util.concurrent.ConcurrentHashMap.newKeySet();

    volatile long lastStatsFlushMs = 0;

    ClientConn(ServerMain server, InetSocketAddress addr) {
        this.server = server;
        this.addr = addr;
    }

    void touch() {
        lastSeenMs = System.currentTimeMillis();
    }

    void onDatagram(String jsonLine) {
        touch();
        JsonNode n = null;
        try {
            n = Net.parse(jsonLine);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        String type = n.path("type").asText("");
        server.onMessage(this, type, n);
    }

    void send(String jsonLine) {
        server.sendTo(addr, jsonLine);
    }

    void sendJson(ObjectNode node) {
        try { send(Net.MAPPER.writeValueAsString(node)); } catch (Exception ignored) {}
    }

    @Override public void close() {
        // No per-client socket to close in UDP mode.
    }
}
