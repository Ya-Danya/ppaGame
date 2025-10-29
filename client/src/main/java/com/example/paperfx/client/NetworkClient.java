package com.example.paperfx.client;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public final class NetworkClient implements Closeable {
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    private final Thread readerThread;

    public final AtomicReference<Messages.State> latestState = new AtomicReference<>();
    public final AtomicReference<Messages.AuthOk> auth = new AtomicReference<>();
    public final AtomicReference<String> status = new AtomicReference<>("connecting");
    public final AtomicReference<String> lastError = new AtomicReference<>(null);

    public NetworkClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        this.readerThread = new Thread(this::readLoop, "net-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public void send(Object msg) {
        try { out.println(Net.toJson(msg)); } catch (Exception ignored) {}
    }

    private void readLoop() {
        try {
            status.set("connected");
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode o = Net.parse(line);
                String type = o.path("type").asText("");
                switch (type) {
                    case "auth_ok" -> auth.set(Net.MAPPER.treeToValue(o, Messages.AuthOk.class));
                    case "state" -> latestState.set(Net.MAPPER.treeToValue(o, Messages.State.class));
                    case "error" -> lastError.set(o.path("reason").asText("error"));
                    default -> { }
                }
            }
            status.set("closed");
        } catch (Exception e) {
            status.set("error: " + e.getMessage());
        }
    }

    @Override public void close() throws IOException { try { socket.close(); } catch (Exception ignored) {} }
}
