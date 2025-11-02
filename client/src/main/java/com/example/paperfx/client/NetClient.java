package com.example.paperfx.client;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class NetClient {
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;

    private BiConsumer<String, JsonNode> onMessage = (t, n) -> {};
    private Runnable onClose = () -> {};

    NetClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    void setOnMessage(BiConsumer<String, JsonNode> onMessage) { this.onMessage = onMessage; }
    void setOnClose(Runnable onClose) { this.onClose = onClose; }

    void connect() throws IOException {
        if (running.get()) return;
        socket = new Socket(host, port);
        socket.setKeepAlive(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        running.set(true);

        readerThread = new Thread(this::readLoop, "net-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    void close() {
        if (!running.compareAndSet(true, false)) return;
        try { socket.close(); } catch (Exception ignored) {}
        onClose.run();
    }

    void send(Object msg) {
        if (!running.get()) return;
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

    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");
                onMessage.accept(type, n);
            }
        } catch (Exception ignored) {
        } finally {
            close();
        }
    }
}
