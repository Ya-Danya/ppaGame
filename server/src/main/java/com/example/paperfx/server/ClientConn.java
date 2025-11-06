package com.example.paperfx.server;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;

final class ClientConn implements Closeable {
    final ServerMain server;
    final Socket socket;
    final BufferedReader in;
    final PrintWriter out;

    volatile boolean authed = false;
    volatile String userId;
    volatile String username;

    volatile String roomId = "MAIN";
    volatile String playerId;
    volatile boolean spectator = false;

    volatile long lastChatMs = 0;

    ClientConn(ServerMain server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    void start() {
        Thread t = new Thread(this::run, "client-" + socket.getPort());
        t.setDaemon(true);
        t.start();
    }

    void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");
                server.onMessage(this, type, n);
            }
        } catch (Exception ignored) {
        } finally {
            server.onDisconnected(this);
        }
    }

    void send(String jsonLine) {
        try { out.println(jsonLine); } catch (Exception ignored) {}
    }

    void sendJson(ObjectNode node) {
        try { send(Net.MAPPER.writeValueAsString(node)); } catch (Exception ignored) {}
    }

    @Override public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }
}
