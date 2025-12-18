package com.example.paperfx.server;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Комната (match) на сервере: игровое поле, список игроков, чат и игровой цикл комнаты.
 * <p>
 * В комнате поддерживаются игроки и наблюдатели (spectator).
 */

final class Room {
    static final int CELL = 10;
    static final int GRID_W = 80;
    static final int GRID_H = 60;

    static final double PLAYER_SIZE = 16;
    static final double PLAYER_SPEED = 240;

    static final int SPAWN_R = 3;
    static final int ROOM_CAPACITY = 4;

    static final long CHAT_COOLDOWN_MS = 5_000;
    static final int CHAT_MAX_LEN = 300;

    final ServerMain server;
    final String roomId;

    final int[] owners = new int[GRID_W * GRID_H];

    final ConcurrentHashMap<String, PlayerEntity> players = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, String> idxToPlayerId = new ConcurrentHashMap<>();

    final Random rnd = new Random();

    Room(ServerMain server, String roomId) {
        this.server = server;
        this.roomId = roomId;
    }

    int toIndex(int x, int y) { return y * GRID_W + x; }

    int countTerritoryCells(int idx) {
        int n = 0;
        for (int o : owners) if (o == idx) n++;
        return n;
    }

    /**
     * Выделяет компактный индекс игрока в диапазоне [1..ROOM_CAPACITY], который сейчас свободен.
     * <p>
     * Возвращает -1, если свободных индексов нет.
     */
    int allocIdx() {
        for (int i = 1; i <= ROOM_CAPACITY; i++) if (!idxToPlayerId.containsKey(i)) return i;
        return -1;
    }

    /**
     * Совместимое имя (алиас), оставлено для совместимости с более ранними изменениями.
     */
    int nextIdx() {
        return allocIdx();
    }

    void giveInitialTerritory(int idx, int cx, int cy) {
        for (int y = cy - SPAWN_R; y <= cy + SPAWN_R; y++) {
            for (int x = cx - SPAWN_R; x <= cx + SPAWN_R; x++) {
                if (x < 0 || y < 0 || x >= GRID_W || y >= GRID_H) continue;
                owners[toIndex(x, y)] = idx;
            }
        }
    }

    void join(ClientConn c, boolean spectator) {
        Room old = (c.roomId != null) ? server.rooms.get(c.roomId) : null;

        if (spectator) {
            // Переход в наблюдателя: удаляем сущность игрока, чтобы пользователь был невиден на поле.
            if (c.playerId != null && old != null) old.removePlayer(c.playerId, false);

            // Сбрасываем накопленную статистику и обнуляем счётчики текущей сессии.
            server.flushUserStats(c, true);
            server.resetSession(c);

            c.playerId = null;
            c.roomId = roomId;
            c.spectator = true;
            sendRoomJoined(c, true, null);
            return;
        }

        // Вход игроком: не покидаем текущую комнату, если целевая переполнена.
        if (players.size() >= ROOM_CAPACITY && (old == null || old != this)) {
            c.sendJson(ServerMain.error("room_full"));
            return;
        }

        // При смене комнаты (или возврате из прошлой игровой сессии) сначала удаляем старого игрока.
        if (c.playerId != null && old != null) old.removePlayer(c.playerId, false);

        server.flushUserStats(c, true);
        server.resetSession(c);

        String pid = UUID.randomUUID().toString();
        int idx = nextIdx();
        if (idx < 0) { // Редко, но на всякий случай обрабатываем гонку/рассинхрон.
            c.sendJson(ServerMain.error("room_full"));
            return;
        }

        int sx = rnd.nextInt(GRID_W);
        int sy = rnd.nextInt(GRID_H);

        double px = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
        double py = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;

        String color = ServerMain.pickColor(roomId, c.username);

        PlayerEntity p = new PlayerEntity(c.userId, c.username, pid, idx, color, c, px, py, sx, sy);
        players.put(pid, p);
        idxToPlayerId.put(idx, pid);

        c.playerId = pid;
        c.roomId = roomId;
        c.spectator = false;

        giveInitialTerritory(idx, sx, sy);

        sendRoomJoined(c, false, pid);
    }

    void sendRoomJoined(ClientConn c, boolean spectator, String pid) {
        ObjectNode msg = Net.MAPPER.createObjectNode();
        msg.put("type", "room_joined");
        msg.put("roomId", roomId);
        msg.put("capacity", ROOM_CAPACITY);
        msg.put("spectator", spectator);
        if (pid != null) msg.put("playerId", pid);
        msg.put("players", players.size());
        c.sendJson(msg);
    }

    void removePlayer(String playerId, boolean keepTerritory) {
        if (playerId == null) return;
        PlayerEntity p = players.remove(playerId);
        if (p == null) return;
        idxToPlayerId.remove(p.idx);
        p.clearTrail();

        // Сохраняем результат игры (лучший счёт / топ) и записываем накопленную статистику.
        try { server.db.recordResult(p.userId, p.score); }
        catch (SQLException e) { System.err.println("[server] recordResult error: " + e.getMessage()); }

        if (p.conn != null) {
            // Поддерживаем кэш лучшего счёта актуальным для профиля/достижений.
            if (p.score > p.conn.bestScore) p.conn.bestScore = p.score;
            server.flushUserStats(p.conn, true);
            server.resetSession(p.conn);
        }

        if (!keepTerritory) {
            for (int i = 0; i < owners.length; i++) if (owners[i] == p.idx) owners[i] = 0;
        }
    }

    void killAndRespawn(PlayerEntity victim, String reason) {
        try { server.db.recordResult(victim.userId, victim.score); }
        catch (SQLException e) { System.err.println("[server] recordResult error: " + e.getMessage()); }

        for (int i = 0; i < owners.length; i++) if (owners[i] == victim.idx) owners[i] = 0;
        victim.clearTrail();

        int sx = rnd.nextInt(GRID_W);
        int sy = rnd.nextInt(GRID_H);

        victim.x = sx * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.y = sy * CELL + (CELL - PLAYER_SIZE) / 2.0;
        victim.cellX = sx;
        victim.cellY = sy;

        giveInitialTerritory(victim.idx, sx, sy);
        victim.deadCooldownTicks = 10;

        System.out.println("[server][" + roomId + "] " + victim.username + " died: " + reason);
    }

    void step(double dt) {
        for (PlayerEntity p : players.values()) {
            if (p.deadCooldownTicks > 0) { p.deadCooldownTicks--; continue; }

            int oldCx = p.cellX;
            int oldCy = p.cellY;

            p.x = ServerMain.clamp(p.x + (p.inputDx * PLAYER_SPEED) * dt, 0, GRID_W * (double) CELL - PLAYER_SIZE);
            p.y = ServerMain.clamp(p.y + (p.inputDy * PLAYER_SPEED) * dt, 0, GRID_H * (double) CELL - PLAYER_SIZE);

            int newCx = ServerMain.clampInt((int) Math.floor((p.x + PLAYER_SIZE / 2.0) / CELL), 0, GRID_W - 1);
            int newCy = ServerMain.clampInt((int) Math.floor((p.y + PLAYER_SIZE / 2.0) / CELL), 0, GRID_H - 1);

            if (newCx != oldCx || newCy != oldCy) {
                for (int[] c : ServerMain.bresenham(oldCx, oldCy, newCx, newCy)) {
                    if (c[0] == oldCx && c[1] == oldCy) continue;
                    p.cellX = c[0];
                    p.cellY = c[1];
                    onEnterCell(p, c[0], c[1]);
                }
            }

            p.score = countTerritoryCells(p.idx);
            if (p.conn != null) {
                if (p.score > p.conn.sessionMaxScore) {
                    p.conn.sessionMaxScore = p.score;
                    if (p.score > p.conn.bestScore) p.conn.bestScore = p.score;
                    server.checkAndUnlockAchievements(p.conn, this);
                }
            }
        }
    }

    private void onEnterCell(PlayerEntity mover, int x, int y) {
        // Если игрок наступает на чужой след — умирает ТОТ, чей след.
        for (PlayerEntity other : players.values()) {
            if (other.idx == mover.idx) continue;
            if (other.deadCooldownTicks > 0) continue;
            if (other.trailSet.contains(ServerMain.key(x, y))) {
                recordKill(mover, other);
                killAndRespawn(other, "trail intersected by " + mover.username);
            }
        }

        int idx = mover.idx;
        boolean inOwnTerritory = owners[toIndex(x, y)] == idx;

        if (!inOwnTerritory) {
            // Вне своей территории: продолжаем след. Захват возможен ТОЛЬКО при возврате на свою территорию.
            addTrail(mover, x, y);
        } else {
            // Возврат на свою территорию замыкает контур и захватывает область внутри.
            if (!mover.trailList.isEmpty()) captureLoopOverwrite(mover);
        }
    }

    private void recordKill(PlayerEntity killer, PlayerEntity victim) {
        if (killer == null || victim == null) return;
        if (killer.conn == null) return;
        ClientConn c = killer.conn;

        c.pendingKills += 1;
        c.statsDirty = true;
        c.sessionKills += 1;
        c.currentKillStreak += 1;
        if (c.currentKillStreak > c.sessionMaxKillStreak) c.sessionMaxKillStreak = c.currentKillStreak;
        if (c.sessionKills > c.bestKillsInGame) c.bestKillsInGame = c.sessionKills;
        if (c.sessionMaxKillStreak > c.bestKillStreak) c.bestKillStreak = c.sessionMaxKillStreak;

        // Проверяем достижения безопасно (вставка идемпотентен; есть кэш в памяти).
        server.checkAndUnlockAchievements(c, this);
    }

    private void addTrail(PlayerEntity p, int x, int y) {
        long k = ServerMain.key(x, y);
        if (p.trailSet.add(k)) p.trailList.add(new Messages.Cell(x, y));
    }

    /**
     * Захват замкнутой области через flood-fill от границ.
     * <p>
     * «Стены» — территория игрока + его след.
     * Всё, что недостижимо снаружи, считается «внутри» и становится территорией игрока.
     * Владельцы перезаписываются (клетки «переходят» захватившему).
     * Сам след также превращается в территорию (включая линии толщиной 1 клетку).
     */
    private void captureLoopOverwrite(PlayerEntity p) {
        int idx = p.idx;
        if (p.trailList.isEmpty()) return;

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

        long gained = 0;

        for (int i = 0; i < owners.length; i++) {
            if (blocked[i] || outside[i]) continue;
            if (owners[i] != idx) gained++;
            owners[i] = idx;
        }

        for (Messages.Cell c : p.trailList) {
            int ti = toIndex(c.x, c.y);
            if (owners[ti] != idx) gained++;
            owners[ti] = idx;
        }

        if (gained > 0 && p.conn != null) {
            p.conn.pendingArea += gained;
            p.conn.statsDirty = true;
            // Достижения могут зависеть от общей захваченной площади.
            server.checkAndUnlockAchievements(p.conn, this);
        }

        p.clearTrail();
    }

    private static void pushIfOpen(ArrayDeque<Integer> q, boolean[] outside, boolean[] blocked, int i) {
        if (blocked[i] || outside[i]) return;
        outside[i] = true;
        q.addLast(i);
    }

    void broadcastState(long tick) {
        int[] ownersSnap = Arrays.copyOf(owners, owners.length);

        List<Messages.Player> ps = new ArrayList<>();
        for (PlayerEntity p : players.values()) {
            List<Messages.Cell> trail = p.trailList.isEmpty() ? null : new ArrayList<>(p.trailList);
            ps.add(new Messages.Player(p.playerId, p.idx, p.username, p.x, p.y, p.score, p.color, trail));
        }
        ps.sort(Comparator.comparingInt((Messages.Player pl) -> pl.score).reversed());

        // Лидерборд по комнате
        List<Messages.LeaderEntry> lb = new ArrayList<>();
        for (Messages.Player pl : ps) lb.add(new Messages.LeaderEntry(pl.username, pl.score));

        Messages.State state = new Messages.State(tick, roomId, CELL, GRID_W, GRID_H, ownersSnap, ps, lb);

        String line;
        try { line = Net.toJson(state); }
        catch (Exception e) { return; }

        server.broadcastToRoom(roomId, line);
    }

    void chatSend(ClientConn from, String text) {
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.length() > CHAT_MAX_LEN) { from.sendJson(ServerMain.error("chat_too_long")); return; }

        long now = System.currentTimeMillis();
        if (now - from.lastChatMs < CHAT_COOLDOWN_MS) { from.sendJson(ServerMain.error("chat_rate_limit")); return; }
        from.lastChatMs = now;

        ObjectNode msg = Net.MAPPER.createObjectNode();
        msg.put("type", "chat_msg");
        msg.put("roomId", roomId);
        msg.put("from", from.username);
        msg.put("text", text);
        msg.put("ts", now);

        server.broadcastJsonToRoom(roomId, msg);
    }

    void systemChat(String text) {
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return;

        long now = System.currentTimeMillis();
        ObjectNode msg = Net.MAPPER.createObjectNode();
        msg.put("type", "chat_msg");
        msg.put("roomId", roomId);
        msg.put("from", "SYSTEM");
        msg.put("text", text);
        msg.put("ts", now);

        server.broadcastJsonToRoom(roomId, msg);
    }
}