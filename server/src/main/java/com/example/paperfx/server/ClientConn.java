package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientConn implements Runnable {
    final Socket socket;
    final BufferedReader in;
    final BufferedWriter out;
    final ServerMain server;

    final AtomicBoolean closed = new AtomicBoolean(false);

    volatile String userId = null;
    volatile String username = null;
    volatile String playerId = null;
    volatile String roomId = null;
    volatile boolean authed = false;

    ClientConn(Socket socket, ServerMain server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.socket.setKeepAlive(true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    void send(Object msg) {
        try {
            String line = Net.toJson(msg);
            synchronized (out) {
                out.write(line);
                out.write("\n");
                out.flush();
            }
        } catch (Exception e) {
            close();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { socket.close(); } catch (IOException ignored) {}
        server.onDisconnected(this);
    }

    @Override public void run() {
        try {
            String line;
            while (!closed.get() && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");
                server.onMessage(this, type, n);
            }
        } catch (Exception ignored) {
        } finally {
            close();
        }
    }

    boolean isAuthed() { return authed && userId != null && username != null; }

    @Override public String toString() {
        return "ClientConn{user=" + username + ", room=" + roomId + ", player=" + playerId + "}";
    }
}
