package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerMain {

    final Db db;

    private final int port;
    private final ServerSocket serverSocket;

    final CopyOnWriteArrayList<ClientConn> clients = new CopyOnWriteArrayList<>();

    // 1 active session per username/userId
    final ConcurrentHashMap<String, ClientConn> activeByUsername = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, ClientConn> activeByUserId = new ConcurrentHashMap<>();

    final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    private final AtomicLong tick = new AtomicLong(0);
    private final Random rnd = new Random();

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 7777;

        String dbUrl = envOr("DB_URL", "jdbc:postgresql://127.0.0.1:5433/paperfx");
        String dbUser = envOr("DB_USER", "paperfx");
        String dbPass = envOr("DB_PASS", "paperfx");

        ServerMain s = new ServerMain(port, new Db(dbUrl, dbUser, dbPass));
        s.start();
    }

    public ServerMain(int port, Db db) throws IOException, SQLException {
        this.port = port;
        this.db = db;
        this.db.init();

        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress("0.0.0.0", port));

        rooms.putIfAbsent("MAIN", new Room(this, "MAIN"));
    }

    public void start() {
        System.out.println("[server] listening on 0.0.0.0:" + port);

        Thread acceptor = new Thread(this::acceptLoop, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-loop");
            t.setDaemon(true);
            return t;
        });

        final long periodMs = 50;

        final long[] lastNs = { System.nanoTime() };

        loop.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            double dt = (now - lastNs[0]) / 1_000_000_000.0;
            lastNs[0] = now;

            for (Room room : rooms.values()) room.step(dt);
            long t = tick.incrementAndGet();
            for (Room room : rooms.values()) room.broadcastState(t);

            cleanupEmptyRooms();
        }, 0, periodMs, TimeUnit.MILLISECONDS);

        try { while (true) Thread.sleep(10_000); } catch (InterruptedException ignored) {}
    }

    private void acceptLoop() {
        while (true) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);

                ClientConn c = new ClientConn(this, s);
                clients.add(c);
                c.start();

                System.out.println("[server] client connected: " + s.getRemoteSocketAddress());
            } catch (IOException e) {
                System.err.println("[server] accept error: " + e.getMessage());
            }
        }
    }

    Room getOrCreateRoom(String roomId) {
        String id = (roomId == null || roomId.isBlank()) ? "MAIN" : roomId.trim();
        if (id.length() > 32) id = id.substring(0, 32);
        String finalId = id;
        return rooms.computeIfAbsent(finalId, rid -> new Room(this, rid));
    }

    // ---- called by ClientConn ----

    void onMessage(ClientConn c, String type, JsonNode n) {
        try {
            switch (type) {
                case "register" -> onRegister(c, n);
                case "login" -> onLogin(c, n);
                case "input" -> onInput(c, n);
                case "create_room" -> onCreateRoom(c, n);
                case "join_room" -> onJoinRoom(c, n);
                case "chat_send" -> onChatSend(c, n);
                case "set_emoji" -> onSetEmoji(c, n);
                case "get_profile" -> onGetProfile(c);
                case "ping" -> { /* keepalive */ }
                default -> c.sendJson(error("unknown_message"));
            }
        } catch (Exception e) {
            c.sendJson(error("server_error"));
        }
    }

    void onDisconnected(ClientConn c) {
        clients.remove(c);

        if (c.username != null) activeByUsername.remove(c.username, c);
        if (c.userId != null) activeByUserId.remove(c.userId, c);

        if (c.roomId != null) {
            Room room = rooms.get(c.roomId);
            if (room != null) {
                if (c.spectator) room.removeSpectator(c);
                else if (c.playerId != null) room.removePlayer(c.playerId, false);
            }
        }

        try { c.close(); } catch (Exception ignored) {}

        try {
            System.out.println("[server] client disconnected: " + c.socket.getRemoteSocketAddress());
        } catch (Exception ignored) {}
    }

    // ---- handlers ----

    private void onRegister(ClientConn c, JsonNode n) throws SQLException {
        String u = n.path("username").asText("");
        String p = n.path("password").asText("");
        Db.RegisterResult r = db.register(u, p);
        if (!r.ok()) { c.sendJson(error(r.error())); return; }
        // auto-login
        ObjectNode login = Net.MAPPER.createObjectNode();
        login.put("type", "login");
        login.put("username", u);
        login.put("password", p);
        onLogin(c, login);
    }

    private void onLogin(ClientConn c, JsonNode n) throws SQLException {
        if (c.authed) { c.sendJson(error("already_authenticated")); return; }

        String u = n.path("username").asText("");
        String p = n.path("password").asText("");

        Db.LoginResult r = db.login(u, p);
        if (!r.ok()) { c.sendJson(error(r.error())); return; }

        // 1 active session per username: new login kicks old
        ClientConn prev = activeByUsername.put(r.username(), c);
        if (prev != null && prev != c) {
            prev.sendJson(error("kicked_duplicate_login"));
            try { prev.close(); } catch (Exception ignored) {}
        }

        c.authed = true;
        c.userId = r.userId();
        c.username = r.username();
        c.selectedEmoji = r.selectedEmoji() == null ? "" : r.selectedEmoji();

        activeByUserId.put(c.userId, c);

        // auth_ok
        try {
            c.send(Net.toJson(new Messages.AuthOk(c.userId, c.username, "", 0, pickColor("MAIN", c.username), r.bestScore())));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // send profile (emojis + stats)
        sendProfileTo(c);

        // Auto-join MAIN
        Room main = getOrCreateRoom("MAIN");
        if (main.players.size() >= Room.ROOM_CAPACITY) {
            String id = "R" + Integer.toHexString(rnd.nextInt()).replace("-", "");
            getOrCreateRoom(id).join(c, false);
        } else {
            main.join(c, false);
        }
    }

    private void onGetProfile(ClientConn c) throws SQLException {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        sendProfileTo(c);
    }

    private void sendProfileTo(ClientConn c) throws SQLException {
        Db.Profile p = db.getProfile(c.userId);
        if (p == null) return;
        c.selectedEmoji = p.selectedEmoji();

        Messages.Profile msg = new Messages.Profile(
                c.username,
                p.selectedEmoji(),
                p.unlockedEmojis(),
                p.totalKills(),
                p.totalScore(),
                p.maxMatchScore(),
                p.maxMatchKills(),
                p.maxKillStreak()
        );
        try {
            c.send(Net.toJson(msg));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void onSetEmoji(ClientConn c, JsonNode n) throws SQLException {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        String emoji = n.path("emoji").asText("");
        boolean ok = db.setEmoji(c.userId, emoji);
        if (!ok) { c.sendJson(error("emoji_not_unlocked")); return; }
        sendProfileTo(c); // refresh after change
    }

    private void onInput(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        if (c.spectator) return;

        Room room = rooms.get(c.roomId);
        if (room == null) return;
        PlayerEntity p = room.players.get(c.playerId);
        if (p == null) return;

        int dx = clampDir(n.path("dx").asInt(0));
        int dy = clampDir(n.path("dy").asInt(0));
        if (dx != 0 && dy != 0) dy = 0;

        p.inputDx = dx;
        p.inputDy = dy;
    }

    private void onCreateRoom(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        String id = n.path("roomId").asText("");
        if (id == null || id.isBlank()) id = "R" + Integer.toHexString(rnd.nextInt()).replace("-", "");
        getOrCreateRoom(id).join(c, false);
    }

    private void onJoinRoom(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        String id = n.path("roomId").asText("");
        boolean spectator = n.path("spectator").asBoolean(false);
        getOrCreateRoom(id).join(c, spectator);
    }

    private void onChatSend(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        Room room = rooms.get(c.roomId);
        if (room == null) return;
        String text = n.path("text").asText("");
        room.chatSend(c, text);
    }

    // ---- achievements push from game events ----

    void pushUnlocked(String userId, List<Db.AchievementUnlock> unlocked) {
        if (unlocked == null || unlocked.isEmpty()) return;
        ClientConn c = activeByUserId.get(userId);
        if (c == null) return;

        for (Db.AchievementUnlock a : unlocked) {
            try {
                c.send(Net.toJson(new Messages.AchievementUnlocked(a.code(), a.title(), a.emoji())));
            } catch (Exception ignored) {}
        }

        try { sendProfileTo(c); } catch (Exception ignored) {}
    }

    String displayName(String userId, String username) {
        ClientConn c = activeByUserId.get(userId);
        String emoji = (c == null) ? "" : (c.selectedEmoji == null ? "" : c.selectedEmoji.trim());
        return emoji.isBlank() ? username : (emoji + " " + username);
    }

    // ---- broadcasting ----

    void broadcastToRoom(String roomId, String jsonLine) {
        for (ClientConn c : clients) {
            if (!c.authed) continue;
            if (!roomId.equals(c.roomId)) continue;
            c.send(jsonLine);
        }
    }

    void broadcastJsonToRoom(String roomId, ObjectNode msg) {
        try { broadcastToRoom(roomId, Net.MAPPER.writeValueAsString(msg)); }
        catch (Exception ignored) {}
    }

    private void cleanupEmptyRooms() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Room> e : rooms.entrySet()) {
            String id = e.getKey();
            if ("MAIN".equals(id)) continue;
            Room r = e.getValue();
            if (r == null) continue;
            if (r.isCompletelyEmpty() && now - r.lastActivityMs() > 60_000) { // 60s idle
                rooms.remove(id, r);
            }
        }
    }


    // ---- utils ----

    static ObjectNode error(String reason) {
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "error");
        n.put("reason", reason);
        return n;
    }

    static String pickColor(String roomId, String username) {
        String[] palette = new String[] {
                "#4CC9F0", "#F72585", "#B5179E", "#4895EF",
                "#43AA8B", "#F9C74F", "#F94144", "#90BE6D"
        };
        int h = Math.abs(Objects.hash(roomId, username));
        return palette[h % palette.length];
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static long key(int x, int y) { return (((long) x) << 32) ^ (y & 0xffffffffL); }

    private static int clampDir(int v) { return v < 0 ? -1 : (v > 0 ? 1 : 0); }

    static List<int[]> bresenham(int x0, int y0, int x1, int y1) {
        ArrayList<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        int x = x0, y = y0;
        while (true) {
            out.add(new int[]{x, y});
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return out;
    }

    static String envOr(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
