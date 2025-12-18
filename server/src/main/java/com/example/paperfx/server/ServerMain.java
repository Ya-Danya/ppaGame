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
    private final DatagramSocket udpSocket;

    final CopyOnWriteArrayList<ClientConn> clients = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<InetSocketAddress, ClientConn> clientsByAddr = new ConcurrentHashMap<>();

    final ConcurrentHashMap<String, ClientConn> activeByUsername = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    private final AtomicLong tick = new AtomicLong(0);
    private final Random rnd = new Random();

    private final AtomicLong roomSeq = new AtomicLong(1);

    // ---- achievements ----
    enum AchMetric { TOTAL_KILLS, TOTAL_AREA, BEST_SCORE, BEST_KILLS_IN_GAME, BEST_KILL_STREAK }

    record AchievementDef(String code, String title, String desc, AchMetric metric, long threshold) {}

    private static final List<AchievementDef> ACHIEVEMENTS = List.of(
            new AchievementDef("KILLS_1", "First Blood", "Kill 1 player in total.", AchMetric.TOTAL_KILLS, 1),
            new AchievementDef("KILLS_10", "Hunter", "Kill 10 players in total.", AchMetric.TOTAL_KILLS, 10),
            new AchievementDef("KILLS_50", "Predator", "Kill 50 players in total.", AchMetric.TOTAL_KILLS, 50),

            new AchievementDef("AREA_100", "Pioneer", "Capture 100 cells in total.", AchMetric.TOTAL_AREA, 100),
            new AchievementDef("AREA_1000", "Conqueror", "Capture 1,000 cells in total.", AchMetric.TOTAL_AREA, 1000),
            new AchievementDef("AREA_5000", "Empire", "Capture 5,000 cells in total.", AchMetric.TOTAL_AREA, 5000),

            new AchievementDef("SCORE_100", "Getting Started", "Reach score 100.", AchMetric.BEST_SCORE, 100),
            new AchievementDef("SCORE_500", "Big Player", "Reach score 500.", AchMetric.BEST_SCORE, 500),
            new AchievementDef("SCORE_1000", "Unstoppable", "Reach score 1000.", AchMetric.BEST_SCORE, 1000),

            new AchievementDef("KILLS_GAME_3", "Rampage", "Kill 3 players in a single game.", AchMetric.BEST_KILLS_IN_GAME, 3),
            new AchievementDef("KILLS_GAME_5", "Slayer", "Kill 5 players in a single game.", AchMetric.BEST_KILLS_IN_GAME, 5),

            new AchievementDef("STREAK_3", "Killing Spree", "Achieve a 3 kill streak.", AchMetric.BEST_KILL_STREAK, 3),
            new AchievementDef("STREAK_5", "Dominating", "Achieve a 5 kill streak.", AchMetric.BEST_KILL_STREAK, 5)
    );

    private static final Map<String, AchievementDef> ACH_BY_CODE;
    static {
        Map<String, AchievementDef> m = new HashMap<>();
        for (AchievementDef d : ACHIEVEMENTS) m.put(d.code(), d);
        ACH_BY_CODE = Collections.unmodifiableMap(m);
    }

    private static final long STATS_FLUSH_INTERVAL_MS = 30_000;
    private static final long CLIENT_IDLE_TIMEOUT_MS = 90_000; // UDP: remove dead sessions after 90s

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

        this.udpSocket = new DatagramSocket(null);
        this.udpSocket.setReuseAddress(true);
        this.udpSocket.bind(new InetSocketAddress("0.0.0.0", port));

        rooms.putIfAbsent("MAIN", new Room(this, "MAIN"));
    }

    public void start() {
        System.out.println("[server][UDP] listening on 0.0.0.0:" + port);

        Thread receiver = new Thread(this::receiveLoop, "udp-receiver");
        receiver.setDaemon(true);
        receiver.start();

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

            // Snapshot to avoid concurrent modification when rooms are added/removed.
            List<Room> snap = new ArrayList<>(rooms.values());

            for (Room room : snap) room.step(dt);
            long t = tick.incrementAndGet();
            for (Room room : snap) room.broadcastState(t);

            // Periodic cleanup of empty rooms (keep MAIN forever).
            if (t % 20 == 0) cleanupEmptyRooms();
            if (t % 600 == 0) flushAllUserStats(false);
            if (t % 40 == 0) cleanupIdleClients();

        }, 0, periodMs, TimeUnit.MILLISECONDS);

        try { while (true) Thread.sleep(10_000); } catch (InterruptedException ignored) {}
    }

    private void receiveLoop() {
        // UDP: one datagram == one JSON message (ASCII/UTF-8)
        byte[] buf = new byte[64 * 1024];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (true) {
            try {
                udpSocket.receive(pkt);
                int len = pkt.getLength();
                if (len <= 0) continue;

                String line = new String(pkt.getData(), pkt.getOffset(), len, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (line.isEmpty()) continue;

                InetSocketAddress addr = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                ClientConn c = clientsByAddr.computeIfAbsent(addr, a -> {
                    ClientConn cc = new ClientConn(this, a);
                    clients.add(cc);
                    System.out.println("[server][UDP] client seen: " + a);
                    return cc;
                });

                try {
                    c.onDatagram(line);
                } catch (Exception e) {
                    c.sendJson(error("bad_message"));
                }
            } catch (IOException e) {
                System.err.println("[server][UDP] receive error: " + e.getMessage());
            }
        }
    }

    void sendTo(InetSocketAddress addr, String jsonLine) {
        if (addr == null || jsonLine == null) return;
        try {
            byte[] data = (jsonLine + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort());
            udpSocket.send(p);
        } catch (Exception ignored) {}
    }

    private void cleanupIdleClients() {
        long now = System.currentTimeMillis();
        for (ClientConn c : new ArrayList<>(clients)) {
            if (now - c.lastSeenMs <= CLIENT_IDLE_TIMEOUT_MS) continue;
            // Consider idle as disconnected
            onDisconnected(c);
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
                case "profile_get" -> onProfileGet(c);
                case "ping" -> { /* keepalive */ }
                default -> c.sendJson(error("unknown_message"));
            }
        } catch (Exception e) {
            c.sendJson(error("server_error"));
        }
    }

    void onDisconnected(ClientConn c) {
        if (c == null) return;

        clients.remove(c);
        clientsByAddr.remove(c.addr, c);

        // Flush any pending stats for spectators / disconnected clients.
        flushUserStats(c, true);

        if (c.username != null) activeByUsername.remove(c.username, c);

        if (c.roomId != null) {
            Room room = rooms.get(c.roomId);
            if (room != null && c.playerId != null) room.removePlayer(c.playerId, false);
            // Remove empty rooms (except MAIN)
            cleanupEmptyRooms();
        }

        try {
            System.out.println("[server][UDP] client removed: " + c.addr);
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
            // UDP: treat as disconnected and remove mapping
            onDisconnected(prev);
        }

        c.authed = true;
        c.userId = r.userId();
        c.username = r.username();

        // Load user stats and unlocked achievements cache
        Db.UserStats us = db.loadOrCreateStats(c.userId);
        c.killsTotal = us.kills();
        c.areaTotal = us.area();
        c.bestKillsInGame = us.bestKillsInGame();
        c.bestKillStreak = us.bestKillStreak();
        c.bestScore = r.bestScore();

        c.unlockedAchievements.clear();
        c.unlockedAchievements.addAll(db.listAchievementCodes(c.userId));

        try {
            c.send(Net.toJson(new Messages.AuthOk(c.userId, c.username, "", 0, pickColor("MAIN", c.username), r.bestScore())));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // Auto-join MAIN (or another non-full room; otherwise auto-create a new room)
        Room main = getOrCreateRoom("MAIN");
        if (main.players.size() < Room.ROOM_CAPACITY) {
            main.join(c, false);
        } else {
            Room target = null;
            for (Room room : rooms.values()) {
                if ("MAIN".equals(room.roomId)) continue;
                if (room.players.size() < Room.ROOM_CAPACITY) { target = room; break; }
            }
            if (target == null) target = getOrCreateRoom(newAutoRoomId());
            target.join(c, false);
        }
    }

    /**
     * Generates a unique id for an auto-created room.
     * Example: AUTO1, AUTO2, ...
     */
    private String newAutoRoomId() {
        while (true) {
            String id = "AUTO" + roomSeq.getAndIncrement();
            if (id.length() > 32) id = id.substring(0, 32);
            if (!rooms.containsKey(id)) return id;
        }
    }

    /**
     * Removes rooms that have no connected authenticated clients.
     * MAIN is never removed.
     */
    private void cleanupEmptyRooms() {
        // Determine which roomIds are currently occupied by any authenticated connection (players or spectators).
        HashSet<String> occupied = new HashSet<>();
        for (ClientConn c : clients) {
            if (!c.authed) continue;
            if (c.roomId == null) continue;
            occupied.add(c.roomId);
        }

        for (String roomId : new ArrayList<>(rooms.keySet())) {
            if ("MAIN".equals(roomId)) continue;
            if (!occupied.contains(roomId)) rooms.remove(roomId);
        }
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
        cleanupEmptyRooms();
    }

    private void onJoinRoom(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        String id = n.path("roomId").asText("");
        boolean spectator = n.path("spectator").asBoolean(false);
        getOrCreateRoom(id).join(c, spectator);
        cleanupEmptyRooms();
    }

    private void onChatSend(ClientConn c, JsonNode n) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        Room room = rooms.get(c.roomId);
        if (room == null) return;
        String text = n.path("text").asText("");
        room.chatSend(c, text);
    }

    private void onProfileGet(ClientConn c) {
        if (!c.authed) { c.sendJson(error("not_authenticated")); return; }
        try {
            Db.UserStats us = db.loadOrCreateStats(c.userId);
            ObjectNode msg = Net.MAPPER.createObjectNode();
            msg.put("type", "profile");
            msg.put("username", c.username);
            msg.put("killsTotal", us.kills());
            msg.put("areaTotal", us.area());
            msg.put("bestScore", c.bestScore);
            msg.put("bestKillsInGame", us.bestKillsInGame());
            msg.put("bestKillStreak", us.bestKillStreak());

            // Achievements list
            var arr = msg.putArray("achievements");
            for (String ac : db.listAchievementCodes(c.userId)) arr.add(ac);

            c.sendJson(msg);
        } catch (Exception e) {
            c.sendJson(error("profile_error"));
        }
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
        try {
            broadcastToRoom(roomId, Net.MAPPER.writeValueAsString(msg));
        } catch (Exception ignored) {}
    }

    // ---- achievements + stats flushing ----

    void resetSession(ClientConn c) {
        if (c == null) return;
        c.sessionKills = 0;
        c.currentKillStreak = 0;
        c.sessionMaxKillStreak = 0;
        c.sessionMaxScore = 0;
    }

    void flushAllUserStats(boolean force) {
        for (ClientConn c : clients) flushUserStats(c, force);
    }

    void flushUserStats(ClientConn c, boolean force) {
        if (c == null) return;
        if (!c.authed) return;

        long now = System.currentTimeMillis();
        if (!force) {
            if (!c.statsDirty) return;
            if (now - c.lastStatsFlushMs < STATS_FLUSH_INTERVAL_MS) return;
        }

        long dk = c.pendingKills;
        long da = c.pendingArea;

        if (dk == 0 && da == 0 && !force) return;

        // Move pending to totals (optimistic)
        c.pendingKills = 0;
        c.pendingArea = 0;
        c.statsDirty = false;
        c.lastStatsFlushMs = now;

        c.killsTotal += dk;
        c.areaTotal += da;

        try {
            db.applyStats(c.userId, dk, da, c.bestKillsInGame, c.bestKillStreak);
        } catch (Exception e) {
            // restore pending if failed
            c.pendingKills += dk;
            c.pendingArea += da;
            c.statsDirty = true;
        }
    }

    void checkAndUnlockAchievements(ClientConn c, Room room) {
        if (c == null || !c.authed) return;
        if (room == null) return;

        // Opportunistically flush stats if a long time has passed
        flushUserStats(c, false);

        long totalKills = c.killsTotal + c.pendingKills;
        long totalArea = c.areaTotal + c.pendingArea;

        long bestScore = c.bestScore;
        long bestKillsInGame = c.bestKillsInGame;
        long bestKillStreak = c.bestKillStreak;

        for (AchievementDef def : ACHIEVEMENTS) {
            if (c.unlockedAchievements.contains(def.code())) continue;

            long val;
            switch (def.metric()) {
                case TOTAL_KILLS -> val = totalKills;
                case TOTAL_AREA -> val = totalArea;
                case BEST_SCORE -> val = bestScore;
                case BEST_KILLS_IN_GAME -> val = bestKillsInGame;
                case BEST_KILL_STREAK -> val = bestKillStreak;
                default -> val = 0;
            }

            if (val >= def.threshold()) {
                // Persist idempotently
                try {
                    boolean inserted = db.unlockAchievement(c.userId, def.code());
                    if (inserted) {
                        c.unlockedAchievements.add(def.code());
                        room.systemChat("🏆 " + c.username + " unlocked achievement: " + def.title());
                    }
                } catch (Exception ignored) {}
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
