package com.example.paperfx.server;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ClientConn implements Closeable {
    final ServerMain server;
    final Socket socket;

    final BufferedReader in;
    final BufferedWriter bout;

    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final AtomicReference<String> latestState = new AtomicReference<>(null);

    private final AtomicBoolean alive = new AtomicBoolean(true);

    volatile boolean authed = false;

    volatile String userId;
    volatile String username;
    volatile String selectedEmoji = "";

    volatile String roomId = "MAIN";
    volatile String playerId = null;
    volatile boolean spectator = false;

    volatile long lastChatMs = 0;

    ClientConn(ServerMain server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.bout = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
    }

    void start() {
        Thread reader = new Thread(this::readLoop, "client-reader");
        reader.setDaemon(true);
        reader.start();

        Thread writer = new Thread(this::writeLoop, "client-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void readLoop() {
        try {
            String line;
            while (alive.get() && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");
                server.onMessage(this, type, n);
            }
        } catch (Exception ignored) {
        } finally {
            alive.set(false);
            server.onDisconnected(this);
        }
    }

    /**
     * Async writer: prevents the game loop from blocking on slow clients.
     * Also drops intermediate 'state' messages by keeping only the latest.
     */
    private void writeLoop() {
        try {
            while (alive.get()) {
                // 1) send any non-state messages
                String msg = outbox.poll(50, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    writeLine(msg);
                    continue;
                }
                // 2) send the newest state snapshot (if any)
                String st = latestState.getAndSet(null);
                if (st != null) {
                    writeLine(st);
                }
            }
        } catch (Exception ignored) {
        } finally {
            alive.set(false);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void writeLine(String jsonLine) throws IOException {
        bout.write(jsonLine);
        bout.newLine();
        bout.flush();
    }

    void send(String jsonLine) {
        if (!alive.get()) return;

        // Treat state messages specially to avoid backlog (keeps latency low).
        if (jsonLine != null && (jsonLine.indexOf("\"type\":\"state\"") >= 0 || jsonLine.indexOf("\"type\": \"state\"") >= 0)) {
            latestState.set(jsonLine);
            return;
        }

        // Cap queue to avoid runaway memory if client is extremely slow.
        if (outbox.size() > 2000) {
            // Drop oldest non-critical messages; keep connection alive.
            outbox.poll();
        }
        outbox.offer(jsonLine);
    }

    void sendJson(ObjectNode node) {
        try { send(Net.MAPPER.writeValueAsString(node)); }
        catch (Exception ignored) {}
    }

    @Override public void close() {
        alive.set(false);
        try { socket.close(); } catch (Exception ignored) {}
    }
}
