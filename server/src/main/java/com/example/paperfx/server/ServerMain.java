package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerMain {

    private static final int CELL = 10;
    private static final int GRID_W = 80;
    private static final int GRID_H = 60;
    private static final double WORLD_W = GRID_W * (double) CELL;
    private static final double WORLD_H = GRID_H * (double) CELL;

    private static final double PLAYER_SIZE = 16;
    private static final double PLAYER_SPEED = 240; // px/s

    private static final int SPAWN_R = 3;

    private final int port;
    private final ServerSocket serverSocket;

    private final CopyOnWriteArrayList<ClientConn> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PlayerEntity> players = new ConcurrentHashMap<>();

    private final int[] owners = new int[GRID_W * GRID_H]; // 0=none, else idx

    private final Random rnd = new Random();
    private final AtomicLong tick = new AtomicLong(0);
    private final AtomicInteger nextIdx = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 7777;
        new ServerMain(port).start();
    }

    public ServerMain(int port) throws IOException {
        this.port = port;
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

        final long periodMs = 50; // 20 TPS
        final long[] lastNs = { System.nanoTime() };

        loop.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            double dt = (now - lastNs[0]) / 1_000_000_000.0;
            lastNs[0] = now;

            step(dt);
            broadcastState();
        }, 0, periodMs, TimeUnit.MILLISECONDS);

        try {
            while (true) Thread.sleep(10_000);
        } catch (InterruptedException ignored) {}
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

            double vx = p.inputDx * PLAYER_SPEED;
            double vy = p.inputDy * PLAYER_SPEED;
            p.x = clamp(p.x + vx * dt, 0, WORLD_W - PLAYER_SIZE);
            p.y = clamp(p.y + vy * dt, 0, WORLD_H - PLAYER_SIZE);

            int newCx = (int) Math.floor((p.x + PLAYER_SIZE / 2.0) / CELL);
            int newCy = (int) Math.floor((p.y + PLAYER_SIZE / 2.0) / CELL);
            newCx = clampInt(newCx, 0, GRID_W - 1);
            newCy = clampInt(newCy, 0, GRID_H - 1);

            if (newCx != oldCx || newCy != oldCy) {
                // walk all crossed cells so trail has no gaps
                for (int[] c : bresenham(oldCx, oldCy, newCx, newCy)) {
                    // skip the first point (old cell): it was already processed previously
                    if (c[0] == oldCx && c[1] == oldCy) continue;
                    p.cellX = c[0];
                    p.cellY = c[1];
                    onEnterCell(p, c[0], c[1]);
                }
            }
        }
        tick.incrementAndGet();
    }

    private void onEnterCell(PlayerEntity mover, int x, int y) {
        // if mover enters someone else's trail -> trail owner dies
        for (PlayerEntity other : players.values()) {
            if (other.idx == mover.idx) continue;
            if (other.deadCooldownTicks > 0) continue;
            if (other.trailSet.contains(key(x, y))) {
                killAndRespawn(other, "trail intersected by " + mover.name);
            }
        }

        int idx = mover.idx;
        boolean inOwnTerritory = owners[toIndex(x, y)] == idx;

        boolean inOwnTrailBefore = mover.trailSet.contains(key(x, y));

        if (!inOwnTerritory) {
            addTrailCell(mover, x, y);

            // self-intersection closes loop
            if (inOwnTrailBefore && mover.trailList.size() >= 4) {
                captureLoopGlobal(mover);
            }
        } else {
            // return to own territory closes loop
            if (!mover.trailList.isEmpty()) {
                captureLoopGlobal(mover);
            }
        }
    }

    private void addTrailCell(PlayerEntity p, int x, int y) {
        long k = key(x, y);
        if (p.trailSet.add(k)) {
            p.trailList.add(new Messages.Cell(x, y));
        }
    }

    private void killAndRespawn(PlayerEntity victim, String reason) {
        int idx = victim.idx;

        // wipe territory
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == idx) owners[i] = 0;
        }
        victim.clearTrail();
        victim.score = 0;

        // respawn
        int sx = rnd.nextInt(GRID_W);
        int sy = rnd.nextInt(GRID_H);
        victim.x = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.y = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.cellX = sx;
        victim.cellY = sy;
        giveInitialTerritory(idx, sx, sy);

        victim.deadCooldownTicks = 10;
        System.out.println("[server] " + victim.name + " died: " + reason);
    }

    /**
     * Global capture:
     * blocked = (owners==idx) OR (trail)
     * flood from border through NOT blocked => outside
     * not blocked & not outside => enclosed => becomes idx
     *
     * Points gained = number of cells set to idx that previously were not idx (including trail).
     */
    private void captureLoopGlobal(PlayerEntity p) {
        int idx = p.idx;
        if (p.trailList.size() < 2) { p.clearTrail(); return; }

        boolean[] blocked = new boolean[owners.length];
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == idx) blocked[i] = true;
        }
        for (Messages.Cell c : p.trailList) {
            blocked[toIndex(c.x, c.y)] = true;
        }

        boolean[] outside = new boolean[owners.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();

        // border seeds
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
            int x = cur % GRID_W;
            int y = cur / GRID_W;

            if (x > 0) pushIfOpen(q, outside, blocked, cur - 1);
            if (x + 1 < GRID_W) pushIfOpen(q, outside, blocked, cur + 1);
            if (y > 0) pushIfOpen(q, outside, blocked, cur - GRID_W);
            if (y + 1 < GRID_H) pushIfOpen(q, outside, blocked, cur + GRID_W);
        }

        int gained = 0;

        // enclosed
        for (int i = 0; i < owners.length; i++) {
            if (blocked[i] || outside[i]) continue;
            if (owners[i] != idx) gained++;
            owners[i] = idx;
        }

        // trail becomes territory too
        for (Messages.Cell c : p.trailList) {
            int i = toIndex(c.x, c.y);
            if (owners[i] != idx) gained++;
            owners[i] = idx;
        }

        p.score += gained;
        p.clearTrail();
    }

    private static void pushIfOpen(ArrayDeque<Integer> q, boolean[] outside, boolean[] blocked, int i) {
        if (i < 0) return;
        if (blocked[i] || outside[i]) return;
        outside[i] = true;
        q.addLast(i);
    }

    private void broadcastState() {
        int[] ownersSnap = Arrays.copyOf(owners, owners.length);

        List<Messages.Player> ps = new ArrayList<>();
        for (PlayerEntity p : players.values()) {
            List<Messages.Cell> trail = p.trailList.isEmpty() ? null : new ArrayList<>(p.trailList);
            ps.add(new Messages.Player(p.id, p.idx, p.name, p.x, p.y, p.score, p.color, trail));
        }
        ps.sort(Comparator.comparingInt((Messages.Player pp) -> pp.score).reversed());

        Messages.State msg = new Messages.State(tick.get(), CELL, GRID_W, GRID_H, ownersSnap, ps);

        String line = Net.toJson(msg);
        for (ClientConn c : clients) c.send(line);
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

    // Bresenham line between grid cells (inclusive)
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

    private final class ClientConn {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
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
                    JsonObject o = Net.parse(line);
                    String type = o.get("type").getAsString();
                    if ("hello".equals(type)) {
                        String name = o.has("name") ? o.get("name").getAsString() : "player";
                        onHello(name);
                    } else if ("input".equals(type)) {
                        if (playerId == null) continue;
                        int dx = o.get("dx").getAsInt();
                        int dy = o.get("dy").getAsInt();
                        PlayerEntity p = players.get(playerId);
                        if (p != null) {
                            p.inputDx = clampDir(dx);
                            p.inputDy = clampDir(dy);
                        }
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

        private void onHello(String name) {
            if (playerId != null) return;

            String id = UUID.randomUUID().toString();
            int idx = nextIdx.getAndIncrement();
            String color = randomColorHex();

            int sx = rnd.nextInt(GRID_W);
            int sy = rnd.nextInt(GRID_H);

            double x = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
            double y = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;

            PlayerEntity p = new PlayerEntity(id, idx, name, x, y, color, sx, sy);
            players.put(id, p);
            playerId = id;

            giveInitialTerritory(idx, sx, sy);
            send(Net.toJson(new Messages.Welcome(id, idx, color)));

            System.out.println("[server] hello from " + name + " idx=" + idx + " id=" + id);
        }

        private void cleanup() {
            clients.remove(this);
            if (playerId != null) players.remove(playerId);
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("[server] client disconnected: " + socket.getRemoteSocketAddress());
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
        final String id;
        final int idx;
        final String name;
        final String color;

        volatile double x, y;
        volatile int inputDx = 0, inputDy = 0;

        volatile int score = 0;
        volatile int cellX;
        volatile int cellY;

        volatile int deadCooldownTicks = 0;

        final HashSet<Long> trailSet = new HashSet<>();
        final ArrayList<Messages.Cell> trailList = new ArrayList<>();

        PlayerEntity(String id, int idx, String name, double x, double y, String color, int cellX, int cellY) {
            this.id = id; this.idx = idx; this.name = name;
            this.x = x; this.y = y;
            this.color = color;
            this.cellX = cellX; this.cellY = cellY;
        }

        void clearTrail() { trailSet.clear(); trailList.clear(); }
    }
}
