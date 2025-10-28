package com.example.paperfx.client;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public final class NetworkClient implements Closeable {
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    private final Thread readerThread;

    public final AtomicReference<Messages.State> latestState = new AtomicReference<>();
    public final AtomicReference<String> myId = new AtomicReference<>();
    public final AtomicReference<Integer> myIdx = new AtomicReference<>();
    public final AtomicReference<String> myColor = new AtomicReference<>();
    public final AtomicReference<String> status = new AtomicReference<>("connecting");

    public NetworkClient(String host, int port, String name) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        send(Net.toJson(new Messages.Hello(name)));

        this.readerThread = new Thread(this::readLoop, "net-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public void sendInput(int dx, int dy) {
        send(Net.toJson(new Messages.Input(dx, dy)));
    }

    private void send(String jsonLine) { try { out.println(jsonLine); } catch (Exception ignored) {} }

    private void readLoop() {
        try {
            status.set("connected");
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject o = Net.parse(line);
                String type = o.get("type").getAsString();
                if ("welcome".equals(type)) {
                    myId.set(o.get("id").getAsString());
                    myIdx.set(o.get("idx").getAsInt());
                    myColor.set(o.get("color").getAsString());
                } else if ("state".equals(type)) {
                    Messages.State s = Net.GSON.fromJson(o, Messages.State.class);
                    latestState.set(s);
                }
            }
            status.set("closed");
        } catch (Exception e) {
            status.set("error: " + e.getMessage());
        }
    }

    @Override public void close() throws IOException { try { socket.close(); } catch (Exception ignored) {} }
}
