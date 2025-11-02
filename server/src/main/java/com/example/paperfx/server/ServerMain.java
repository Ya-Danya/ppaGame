package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class ServerMain {
    private static final int PORT = 7777;

    private final Db db;
    private final RoomManager roomManager;

    private final ExecutorService clientPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "client");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tick");
        t.setDaemon(true);
        return t;
    });

    // active sessions by username (enforces 1 session per username)
    private final ConcurrentHashMap<String, ClientConn> activeByUsername = new ConcurrentHashMap<>();

    public ServerMain() throws SQLException {
        String url = env("DB_URL", "jdbc:postgresql://127.0.0.1:5433/paperfx");
        String user = env("DB_USER", "paperfx");
        String pass = env("DB_PASS", "paperfx");

        this.db = new Db(url, user, pass);
        this.db.init();

        this.roomManager = new RoomManager(4, 80, 60, 10);

        // game loop for all rooms
        tick.scheduleAtFixedRate(() -> {
            try {
                for (Room room : roomManager.allRooms()) {
                    room.step();
                    broadcastRoomState(room);
                }
            } catch (Exception ignored) {}
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    public static void main(String[] args) throws Exception {
        new ServerMain().run();
    }

    public void run() throws IOException {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            System.out.println("PaperFX server listening on " + PORT);
            while (true) {
                Socket s = ss.accept();
                ClientConn c = new ClientConn(s, this);
                clientPool.submit(c);
            }
        }
    }

    void onMessage(ClientConn c, String type, JsonNode n) {
        try {
            switch (type) {
                case "ping" -> { /* keepalive */ }
                case "register" -> handleRegister(c, n);
                case "login" -> handleLogin(c, n);
                case "leaderboard_req" -> handleLeaderboard(c, n);
                case "create_room" -> handleCreateRoom(c);
                case "join_room" -> handleJoinRoom(c, n);
                case "input" -> handleInput(c, n);
                default -> c.send(new Messages.Error("unknown message type: " + type));
            }
        } catch (Exception e) {
            c.send(new Messages.Error("server error: " + e.getMessage()));
        }
    }

    private void handleRegister(ClientConn c, JsonNode n) throws SQLException {
        if (c.isAuthed()) {
            c.send(new Messages.Error("already authenticated"));
            return;
        }
        String u = n.path("username").asText("");
        String p = n.path("password").asText("");
        Db.RegisterResult r = db.register(u, p);
        if (!r.ok()) {
            c.send(new Messages.Error(r.error()));
            return;
        }
        // auto-login after register
        finishAuth(c, r.userId(), r.username(), r.bestScore());
    }

    private void handleLogin(ClientConn c, JsonNode n) throws SQLException {
        if (c.isAuthed()) {
            c.send(new Messages.Error("already authenticated"));
            return;
        }
        String u = n.path("username").asText("");
        String p = n.path("password").asText("");
        Db.LoginResult r = db.login(u, p);
        if (!r.ok()) {
            c.send(new Messages.Error(r.error()));
            return;
        }
        finishAuth(c, r.userId(), r.username(), r.bestScore());
    }

    private void finishAuth(ClientConn c, String userId, String username, int bestScore) {
        c.userId = userId;
        c.username = username;
        c.authed = true;

        // enforce 1 active session per username: kick previous
        ClientConn prev = activeByUsername.put(username, c);
        if (prev != null && prev != c) {
            prev.send(new Messages.Error("logged in elsewhere"));
            prev.close();
        }

        c.send(new Messages.AuthOk(userId, username, bestScore));
        // by default, user is not in a room yet; client should join MAIN or create.
    }

    private void handleLeaderboard(ClientConn c, JsonNode n) throws SQLException {
        int limit = n.path("limit").asInt(10);
        List<Messages.LeaderEntry> top = db.topBest(limit);
        // send as state leaderboard-only (client merges)
        c.send(new Messages.State(0, c.roomId, 10, 0, 0, null, List.of(), top));
    }

    private void handleCreateRoom(ClientConn c) {
        if (!c.isAuthed()) { c.send(new Messages.Error("not authenticated")); return; }
        Room r = roomManager.create();
        joinRoomInternal(c, r.roomId);
    }

    private void handleJoinRoom(ClientConn c, JsonNode n) {
        if (!c.isAuthed()) { c.send(new Messages.Error("not authenticated")); return; }
        String rid = n.path("roomId").asText("").trim().toUpperCase(Locale.ROOT);
        if (rid.isEmpty()) { c.send(new Messages.Error("roomId is empty")); return; }
        Room r = roomManager.get(rid);
        if (r == null) {
            c.send(new Messages.Error("room not found"));
            return;
        }
        joinRoomInternal(c, rid);
    }

    private void joinRoomInternal(ClientConn c, String rid) {
        // leave previous room if any
        leaveRoom(c);

        Room r = roomManager.get(rid);
        if (r == null) {
            c.send(new Messages.Error("room not found"));
            return;
        }
        if (r.playersCount() >= r.capacity) {
            c.send(new Messages.Error("room_full"));
            return;
        }

        String playerId = Ids.playerId();
        String color = pickColor(rid, c.username);
        PlayerEntity p = new PlayerEntity(playerId, c.userId, c.username, color);

        boolean ok = r.addPlayer(p);
        if (!ok) {
            c.send(new Messages.Error("room_full"));
            return;
        }

        c.roomId = rid;
        c.playerId = playerId;

        c.send(new Messages.RoomJoined(rid, r.capacity, r.playersCount()));
        // Immediately push a state snapshot for smoother join.
        broadcastRoomState(r);
    }

    private void leaveRoom(ClientConn c) {
        if (c.roomId == null || c.playerId == null) return;
        Room r = roomManager.get(c.roomId);
        if (r != null) {
            r.removePlayer(c.playerId);
        }
        c.roomId = null;
        c.playerId = null;
    }

    private void handleInput(ClientConn c, JsonNode n) {
        if (c.roomId == null || c.playerId == null) return;
        int dx = n.path("dx").asInt(0);
        int dy = n.path("dy").asInt(0);

        Room r = roomManager.get(c.roomId);
        if (r == null) return;
        r.setInput(c.playerId, dx, dy);
    }

    void onDisconnected(ClientConn c) {
        // If authed, record "last score" at disconnect: current territory size.
        try {
            if (c.isAuthed() && c.roomId != null && c.playerId != null) {
                Room r = roomManager.get(c.roomId);
                int score = 0;
                if (r != null) {
                    // compute quickly by counting owners==idx for this player
                    PlayerEntity pe = r.players.get(c.playerId);
                    if (pe != null) {
                        int idx = pe.idx;
                        for (int o : r.owners) if (o == idx) score++;
                    }
                    r.removePlayer(c.playerId);
                }
                db.recordResult(c.userId, score);
            }
        } catch (Exception ignored) {
        } finally {
            if (c.username != null) {
                activeByUsername.compute(c.username, (k, v) -> v == c ? null : v);
            }
        }
    }

    private void broadcastRoomState(Room r) {
        // build state once; send to all clients in that room
        List<Messages.Player> players = r.snapshotPlayers();
        // lightweight: only include persistent leaderboard occasionally (every 20 ticks)
        List<Messages.LeaderEntry> top = List.of();
        if (r.tick() % 20 == 0) {
            try { top = db.topBest(10); } catch (SQLException ignored) {}
        }

        Messages.State st = new Messages.State(
                r.tick(),
                r.roomId,
                r.cellSize,
                r.gridW,
                r.gridH,
                r.owners,
                players,
                top
        );

        for (ClientConn c : activeByUsername.values()) {
            if (c == null || c.closed.get()) continue;
            if (!Objects.equals(c.roomId, r.roomId)) continue;
            c.send(st);
        }
    }

    private static String pickColor(String roomId, String username) {
        // stable-ish color choice from a small palette
        String[] palette = {"#3B82F6", "#22C55E", "#EF4444", "#F59E0B", "#A855F7", "#14B8A6"};
        int h = Math.abs(Objects.hash(roomId, username));
        return palette[h % palette.length];
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }
}
