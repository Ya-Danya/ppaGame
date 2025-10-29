package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerMain {

    private static final int CELL = 10;
    private static final int GRID_W = 80;
    private static final int GRID_H = 60;

    private static final double PLAYER_SIZE = 16;
    private static final double PLAYER_SPEED = 240;

    private static final int SPAWN_R = 3;

    private static final int LEADERBOARD_LIMIT = 10;
    private static final long LEADERBOARD_REFRESH_MS = 2000;

    private final int port;
    private final ServerSocket serverSocket;

    private final CopyOnWriteArrayList<ClientConn> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PlayerEntity> players = new ConcurrentHashMap<>();

    private final int[] owners = new int[GRID_W * GRID_H];

    private final Random rnd = new Random();
    private final AtomicLong tick = new AtomicLong(0);
    private final AtomicInteger nextIdx = new AtomicInteger(1);

    private final Db db;
    private volatile List<Messages.LeaderEntry> cachedLeaderboard = List.of();
    private volatile long lastLbMs = 0;

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 7777;

        String dbUrl = envOr("DB_URL", "jdbc:postgresql://localhost:5432/paperfx");
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

            step(dt);
            refreshLeaderboardIfNeeded();
            broadcastState();
        }, 0, periodMs, TimeUnit.MILLISECONDS);

        try { while (true) Thread.sleep(10_000); } catch (InterruptedException ignored) {}
    }

    private void refreshLeaderboardIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastLbMs < LEADERBOARD_REFRESH_MS) return;
        lastLbMs = now;
        try { cachedLeaderboard = db.topBest(LEADERBOARD_LIMIT); }
        catch (SQLException e) { System.err.println("[server] leaderboard error: " + e.getMessage()); }
    }

    private void acceptLoop() {
        while (true) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(30_000);
                ClientConn conn = new ClientConn(s);
                clients.add(conn);
                conn.start();
                System.out.println("[server] client connected: " + s.getRemoteSocketAddress());
            } catch (IOException e) {
                System.err.println("[server] accept error: " + e.getMessage());
            }
        }
    }

    private void step(double dt) {
        for (PlayerEntity p : players.values()) {
            if (p.deadCooldownTicks > 0) {
                p.deadCooldownTicks--;
                continue;
            }

            int oldCx = p.cellX;
            int oldCy = p.cellY;

            p.x = clamp(p.x + (p.inputDx * PLAYER_SPEED) * dt, 0, GRID_W * (double) CELL - PLAYER_SIZE);
            p.y = clamp(p.y + (p.inputDy * PLAYER_SPEED) * dt, 0, GRID_H * (double) CELL - PLAYER_SIZE);

            int newCx = clampInt((int) Math.floor((p.x + PLAYER_SIZE / 2.0) / CELL), 0, GRID_W - 1);
            int newCy = clampInt((int) Math.floor((p.y + PLAYER_SIZE / 2.0) / CELL), 0, GRID_H - 1);

            if (newCx != oldCx || newCy != oldCy) {
                for (int[] c : bresenham(oldCx, oldCy, newCx, newCy)) {
                    if (c[0] == oldCx && c[1] == oldCy) continue;
                    p.cellX = c[0];
                    p.cellY = c[1];
                    onEnterCell(p, c[0], c[1]);
                }
            }

            p.score = countTerritoryCells(p.idx);
        }
        tick.incrementAndGet();
    }

    private int countTerritoryCells(int idx) {
        int n = 0;
        for (int o : owners) if (o == idx) n++;
        return n;
    }

    private void onEnterCell(PlayerEntity mover, int x, int y) {
        for (PlayerEntity other : players.values()) {
            if (other.idx == mover.idx) continue;
            if (other.deadCooldownTicks > 0) continue;
            if (other.trailSet.contains(key(x, y))) {
                killAndRespawn(other, "trail intersected by " + mover.username);
            }
        }

        int idx = mover.idx;
        boolean inOwnTerritory = owners[toIndex(x, y)] == idx;
        boolean inOwnTrailBefore = mover.trailSet.contains(key(x, y));

        if (!inOwnTerritory) {
            addTrailCell(mover, x, y);
            if (inOwnTrailBefore && mover.trailList.size() >= 4) captureLoopGlobal(mover);
        } else {
            if (!mover.trailList.isEmpty()) captureLoopGlobal(mover);
        }
    }

    private void addTrailCell(PlayerEntity p, int x, int y) {
        long k = key(x, y);
        if (p.trailSet.add(k)) p.trailList.add(new Messages.Cell(x, y));
    }

    private void killAndRespawn(PlayerEntity victim, String reason) {
        int idx = victim.idx;
        try { db.recordResult(victim.userId, victim.score); }
        catch (SQLException e) { System.err.println("[server] recordResult error: " + e.getMessage()); }

        for (int i = 0; i < owners.length; i++) if (owners[i] == idx) owners[i] = 0;
        victim.clearTrail();

        int sx = rnd.nextInt(GRID_W);
        int sy = rnd.nextInt(GRID_H);
        victim.x = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.y = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.cellX = sx;
        victim.cellY = sy;
        giveInitialTerritory(idx, sx, sy);

        victim.deadCooldownTicks = 10;
        System.out.println("[server] " + victim.username + " died: " + reason);
    }

    private void captureLoopGlobal(PlayerEntity p) {
        int idx = p.idx;
        if (p.trailList.size() < 2) { p.clearTrail(); return; }

        boolean[] blocked = new boolean[owners.length];
        for (int i = 0; i < owners.length; i++) if (owners[i] == idx) blocked[i] = true;
        for (Messages.Cell c : p.trailList) blocked[toIndex(c.x, c.y)] = true;

        boolean[] outside = new boolean[owners.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();

        for (int x = 0; x < GRID_W; x++) {
            pushIfOpen(q, outside, blocked, toIndex(x, 0));
            pushIfOpen(q, outside, blocked, toIndex(x, GRID_H - 1));
        }
        for (int y = 0; y < GRID_H; y++) {
            pushIfOpen(q, outside, blocked, toIndex(0, y));
            pushIfOpen(q, outside, blocked, toIndex(GRID_W - 1, y));
        }

        while (!q.isEmpty()) {
            int cur = q.removeFirst();
            int cx = cur % GRID_W;
            int cy = cur / GRID_W;

            if (cx > 0) pushIfOpen(q, outside, blocked, cur - 1);
            if (cx + 1 < GRID_W) pushIfOpen(q, outside, blocked, cur + 1);
            if (cy > 0) pushIfOpen(q, outside, blocked, cur - GRID_W);
            if (cy + 1 < GRID_H) pushIfOpen(q, outside, blocked, cur + GRID_W);
        }

        for (int i = 0; i < owners.length; i++) {
            if (blocked[i] || outside[i]) continue;
            if (owners[i] == 0) owners[i] = idx;
        }
        for (Messages.Cell c : p.trailList) {
            int i = toIndex(c.x, c.y);
            if (owners[i] == 0) owners[i] = idx;
        }

        p.clearTrail();
    }

    private static void pushIfOpen(ArrayDeque<Integer> q, boolean[] outside, boolean[] blocked, int i) {
        if (blocked[i] || outside[i]) return;
        outside[i] = true;
        q.addLast(i);
    }

    private void broadcastState() {
        int[] ownersSnap = Arrays.copyOf(owners, owners.length);

        List<Messages.Player> ps = new ArrayList<>();
        for (PlayerEntity p : players.values()) {
            List<Messages.Cell> trail = p.trailList.isEmpty() ? null : new ArrayList<>(p.trailList);
            ps.add(new Messages.Player(p.playerId, p.idx, p.username, p.x, p.y, p.score, p.color, trail));
        }
        ps.sort(Comparator.comparingInt((Messages.Player pp) -> pp.score).reversed());

        Messages.State msg = new Messages.State(tick.get(), CELL, GRID_W, GRID_H, ownersSnap, ps, cachedLeaderboard);

        try {
            String line = Net.toJson(msg);
            for (ClientConn c : clients) c.send(line);
        } catch (Exception e) {
            System.err.println("[server] json encode error: " + e.getMessage());
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private int toIndex(int x, int y) { return y * GRID_W + x; }
    private static long key(int x, int y) { return (((long) x) << 32) ^ (y & 0xffffffffL); }

    private void giveInitialTerritory(int idx, int cx, int cy) {
        for (int y = cy - SPAWN_R; y <= cy + SPAWN_R; y++) {
            for (int x = cx - SPAWN_R; x <= cx + SPAWN_R; x++) {
                if (x < 0 || y < 0 || x >= GRID_W || y >= GRID_H) continue;
                owners[toIndex(x, y)] = idx;
            }
        }
    }

    private static List<int[]> bresenham(int x0, int y0, int x1, int y1) {
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

    private static String envOr(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private final class ClientConn {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        private volatile boolean authed = false;
        private volatile String userId;
        private volatile String username;
        private volatile String playerId;

        ClientConn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        }

        void start() {
            Thread t = new Thread(this::run, "client-" + socket.getPort());
            t.setDaemon(true);
            t.start();
        }

        void send(String jsonLine) { try { out.println(jsonLine); } catch (Exception ignored) {} }

        void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode o = Net.parse(line);
                    String type = o.path("type").asText("");
                    switch (type) {
                        case "register" -> onRegister(o);
                        case "login" -> onLogin(o);
                        case "input" -> onInput(o);
                        case "leaderboard_req" -> onLeaderboardReq(o);
                        default -> sendSafe(new Messages.Error("unknown message type: " + type));
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[server] client timeout: " + socket.getRemoteSocketAddress());
            } catch (Exception e) {
                System.out.println("[server] client error: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void onRegister(JsonNode o) {
            String u = o.path("username").asText("");
            String p = o.path("password").asText("");
            try {
                Db.RegisterResult r = db.register(u, p);
                if (!r.ok()) { sendSafe(new Messages.Error(r.error())); return; }
                onLogin(o);
            } catch (SQLException e) {
                sendSafe(new Messages.Error("db error: " + e.getMessage()));
            }
        }

        private void onLogin(JsonNode o) {
            String u = o.path("username").asText("");
            String p = o.path("password").asText("");
            try {
                Db.LoginResult r = db.login(u, p);
                if (!r.ok()) { sendSafe(new Messages.Error(r.error())); return; }

                this.authed = true;
                this.userId = r.userId();
                this.username = r.username();

                String pid = UUID.randomUUID().toString();
                int idx = nextIdx.getAndIncrement();
                String color = randomColorHex();

                int sx = rnd.nextInt(GRID_W);
                int sy = rnd.nextInt(GRID_H);

                double px = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
                double py = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;

                PlayerEntity pe = new PlayerEntity(userId, username, pid, idx, color, px, py, sx, sy);
                players.put(pid, pe);
                this.playerId = pid;

                giveInitialTerritory(idx, sx, sy);
                sendSafe(new Messages.AuthOk(userId, username, pid, idx, color, r.bestScore()));
                System.out.println("[server] auth ok: " + username + " idx=" + idx);
            } catch (SQLException e) {
                sendSafe(new Messages.Error("db error: " + e.getMessage()));
            }
        }

        private void onInput(JsonNode o) {
            if (!authed || playerId == null) { sendSafe(new Messages.Error("not authenticated")); return; }
            int dx = clampDir(o.path("dx").asInt(0));
            int dy = clampDir(o.path("dy").asInt(0));
            PlayerEntity p = players.get(playerId);
            if (p != null) { p.inputDx = dx; p.inputDy = dy; }
        }

        private void onLeaderboardReq(JsonNode o) {
            int limit = Math.max(1, Math.min(o.path("limit").asInt(10), 50));
            try { cachedLeaderboard = db.topBest(limit); }
            catch (SQLException e) { sendSafe(new Messages.Error("db error: " + e.getMessage())); }
        }

        private void cleanup() {
            clients.remove(this);
            if (playerId != null) {
                PlayerEntity p = players.remove(playerId);
                if (p != null) {
                    try { db.recordResult(p.userId, p.score); }
                    catch (SQLException e) { System.err.println("[server] recordResult(disconnect) error: " + e.getMessage()); }
                }
            }
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("[server] client disconnected: " + socket.getRemoteSocketAddress());
        }

        private void sendSafe(Object msg) {
            try { send(Net.toJson(msg)); } catch (Exception ignored) {}
        }

        private int clampDir(int v) { return v < 0 ? -1 : (v > 0 ? 1 : 0); }

        private String randomColorHex() {
            int r = 80 + rnd.nextInt(176);
            int g = 80 + rnd.nextInt(176);
            int b = 80 + rnd.nextInt(176);
            return String.format("#%02X%02X%02X", r, g, b);
        }
    }

    private static final class PlayerEntity {
        final String userId;
        final String username;

        final String playerId;
        final int idx;
        final String color;

        volatile double x, y;
        volatile int inputDx = 0, inputDy = 0;

        volatile int score = 0;

        volatile int cellX;
        volatile int cellY;

        volatile int deadCooldownTicks = 0;

        final HashSet<Long> trailSet = new HashSet<>();
        final ArrayList<Messages.Cell> trailList = new ArrayList<>();

        PlayerEntity(String userId, String username, String playerId, int idx, String color,
                     double x, double y, int cellX, int cellY) {
            this.userId = userId;
            this.username = username;
            this.playerId = playerId;
            this.idx = idx;
            this.color = color;
            this.x = x;
            this.y = y;
            this.cellX = cellX;
            this.cellY = cellY;
        }

        void clearTrail() { trailSet.clear(); trailList.clear(); }
    }
}
