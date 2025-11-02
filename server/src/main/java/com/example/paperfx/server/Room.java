package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class Room {
    final String roomId;
    final int capacity;
    final int gridW;
    final int gridH;
    final int cellSize;
    final int[] owners;        // 0 = neutral, else idx of player
    final int[] trailOwners;   // 0 = none, else idx of player trail
    final Map<String, PlayerEntity> players = new ConcurrentHashMap<>();
    private final Random rng = new Random();

    private long tick = 0;

    Room(String roomId, int capacity, int gridW, int gridH, int cellSize) {
        this.roomId = roomId;
        this.capacity = capacity;
        this.gridW = gridW;
        this.gridH = gridH;
        this.cellSize = cellSize;
        this.owners = new int[gridW * gridH];
        this.trailOwners = new int[gridW * gridH];
    }

    int playersCount() { return players.size(); }

    synchronized boolean addPlayer(PlayerEntity p) {
        if (players.size() >= capacity) return false;
        // assign idx must be unique within room
        int idx = allocIdx();
        p.idx = idx;

        // place player
        int[] spawn = findSpawn();
        p.x = spawn[0];
        p.y = spawn[1];
        p.dx = 0;
        p.dy = 0;
        p.trail.clear();
        p.drawing = false;

        // initial territory 3x3, only paint neutral to avoid "spawn steal"
        int sx = (int) p.x;
        int sy = (int) p.y;
        for (int yy = sy - 1; yy <= sy + 1; yy++) {
            for (int xx = sx - 1; xx <= sx + 1; xx++) {
                if (!inside(xx, yy)) continue;
                int i = idx(xx, yy);
                if (owners[i] == 0) owners[i] = idx;
            }
        }

        players.put(p.playerId, p);
        return true;
    }

    synchronized void removePlayer(String playerId) {
        PlayerEntity p = players.remove(playerId);
        if (p == null) return;
        // clear trail cells
        for (Messages.Cell c : p.trail) {
            if (inside(c.x, c.y)) trailOwners[idx(c.x, c.y)] = 0;
        }
        // release territory to neutral
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == p.idx) owners[i] = 0;
            if (trailOwners[i] == p.idx) trailOwners[i] = 0;
        }
    }

    synchronized void setInput(String playerId, int dx, int dy) {
        PlayerEntity p = players.get(playerId);
        if (p == null) return;
        // prevent diagonal
        if (dx != 0 && dy != 0) { dx = 0; dy = 0; }
        // normalize
        if (dx > 0) dx = 1; if (dx < 0) dx = -1;
        if (dy > 0) dy = 1; if (dy < 0) dy = -1;
        p.dx = dx;
        p.dy = dy;
    }

    synchronized void step() {
        tick++;

        // move each player one cell per tick if input present
        for (PlayerEntity p : new ArrayList<>(players.values())) {
            if (p.dx == 0 && p.dy == 0) continue;

            int nx = (int) p.x + p.dx;
            int ny = (int) p.y + p.dy;
            if (!inside(nx, ny)) continue;

            // collision with trails (including self)
            int ti = idx(nx, ny);
            int hitTrailIdx = trailOwners[ti];
            if (hitTrailIdx != 0) {
                // someone hits a trail -> owner of that trail dies
                PlayerEntity victim = findByIdx(hitTrailIdx);
                if (victim != null) {
                    kill(victim, "trail_hit");
                }
                // if you hit your own trail, you die as well in this model
                if (hitTrailIdx == p.idx) {
                    kill(p, "self_trail_hit");
                }
                continue;
            }

            // advance
            p.x = nx;
            p.y = ny;

            int oi = owners[ti];
            if (!p.drawing) {
                // start drawing when leaving own territory
                if (oi != p.idx) {
                    p.drawing = true;
showTrailAdd(p, nx, ny);
                }
            } else {
                // while drawing, mark trail
                showTrailAdd(p, nx, ny);

                if (oi == p.idx) {
                    // closed loop: capture area
                    capture(p);
                }
            }
        }
    }

    private void showTrailAdd(PlayerEntity p, int x, int y) {
        // do not mark trail on own territory cells; keeps trail compact
        if (!inside(x, y)) return;
        int i = idx(x, y);
        if (owners[i] == p.idx) return;
        if (trailOwners[i] == 0) {
            trailOwners[i] = p.idx;
            p.trail.add(new Messages.Cell(x, y));
        }
    }

    private void kill(PlayerEntity victim, String reason) {
        // wipe victim trail and territory; victim respawns immediately (join-in-progress OK)
        for (Messages.Cell c : victim.trail) {
            if (inside(c.x, c.y)) trailOwners[idx(c.x, c.y)] = 0;
        }
        victim.trail.clear();
        victim.drawing = false;

        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == victim.idx) owners[i] = 0;
            if (trailOwners[i] == victim.idx) trailOwners[i] = 0;
        }

        int[] spawn = findSpawn();
        victim.x = spawn[0];
        victim.y = spawn[1];
        victim.dx = 0;
        victim.dy = 0;

        // give tiny starter patch again (neutral only)
        int sx = (int) victim.x;
        int sy = (int) victim.y;
        for (int yy = sy - 1; yy <= sy + 1; yy++) {
            for (int xx = sx - 1; xx <= sx + 1; xx++) {
                if (!inside(xx, yy)) continue;
                int i = idx(xx, yy);
                if (owners[i] == 0) owners[i] = victim.idx;
            }
        }
    }

    private void capture(PlayerEntity p) {
        // Convert trail to solid territory (including stealing other territory)
        for (Messages.Cell c : p.trail) {
            if (!inside(c.x, c.y)) continue;
            int i = idx(c.x, c.y);
            owners[i] = p.idx;
            trailOwners[i] = 0;
        }

        // "walls" = your territory + your trail (already converted to territory)
        boolean[] wall = new boolean[owners.length];
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == p.idx) wall[i] = true;
            if (trailOwners[i] == p.idx) wall[i] = true;
        }

        // flood fill outside region starting from borders
        boolean[] outside = new boolean[owners.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();

        for (int x = 0; x < gridW; x++) {
            enqueueIfOpen(x, 0, wall, outside, q);
            enqueueIfOpen(x, gridH - 1, wall, outside, q);
        }
        for (int y = 0; y < gridH; y++) {
            enqueueIfOpen(0, y, wall, outside, q);
            enqueueIfOpen(gridW - 1, y, wall, outside, q);
        }

        while (!q.isEmpty()) {
            int cur = q.poll();
            int cx = cur % gridW;
            int cy = cur / gridW;
            if (cx > 0) enqueueIfOpen(cx - 1, cy, wall, outside, q);
            if (cx + 1 < gridW) enqueueIfOpen(cx + 1, cy, wall, outside, q);
            if (cy > 0) enqueueIfOpen(cx, cy - 1, wall, outside, q);
            if (cy + 1 < gridH) enqueueIfOpen(cx, cy + 1, wall, outside, q);
        }

        // inside cells are those not outside and not wall -> become your territory
        for (int i = 0; i < owners.length; i++) {
            if (!outside[i] && !wall[i]) {
                owners[i] = p.idx; // steal if needed
            }
        }

        // clear trail state
        p.trail.clear();
        p.drawing = false;
    }

    private void enqueueIfOpen(int x, int y, boolean[] wall, boolean[] outside, ArrayDeque<Integer> q) {
        int i = idx(x, y);
        if (outside[i]) return;
        if (wall[i]) return;
        outside[i] = true;
        q.add(i);
    }

    synchronized List<Messages.Player> snapshotPlayers() {
        // compute scores by counting territory sizes
        int maxIdx = 0;
        for (PlayerEntity p : players.values()) maxIdx = Math.max(maxIdx, p.idx);
        int[] score = new int[Math.max(8, maxIdx + 2)];
        for (int o : owners) {
            if (o > 0 && o < score.length) score[o]++;
        }

        List<Messages.Player> out = new ArrayList<>();
        for (PlayerEntity p : players.values()) {
            out.add(new Messages.Player(
                    p.playerId,
                    p.idx,
                    p.username,
                    p.x,
                    p.y,
                    (p.idx < score.length ? score[p.idx] : 0),
                    p.color,
                    new ArrayList<>(p.trail)
            ));
        }
        out.sort(Comparator.comparingInt(a -> a.idx));
        return out;
    }

    long tick() { return tick; }

    private PlayerEntity findByIdx(int idx) {
        for (PlayerEntity p : players.values()) if (p.idx == idx) return p;
        return null;
    }

    private int allocIdx() {
        // find smallest positive integer not used
        boolean[] used = new boolean[64];
        for (PlayerEntity p : players.values()) {
            if (p.idx > 0 && p.idx < used.length) used[p.idx] = true;
        }
        for (int i = 1; i < used.length; i++) if (!used[i]) return i;
        return 1 + rng.nextInt(60);
    }

    private int[] findSpawn() {
        // try to find neutral area with 3x3 empty-ish
        for (int attempt = 0; attempt < 200; attempt++) {
            int x = 2 + rng.nextInt(gridW - 4);
            int y = 2 + rng.nextInt(gridH - 4);
            boolean ok = true;
            for (int yy = y - 1; yy <= y + 1 && ok; yy++) {
                for (int xx = x - 1; xx <= x + 1; xx++) {
                    int i = idx(xx, yy);
                    if (owners[i] != 0) { ok = false; break; }
                    if (trailOwners[i] != 0) { ok = false; break; }
                }
            }
            if (ok) return new int[]{x, y};
        }
        return new int[]{gridW / 2, gridH / 2};
    }

    private boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < gridW && y < gridH; }
    private int idx(int x, int y) { return y * gridW + x; }
}
