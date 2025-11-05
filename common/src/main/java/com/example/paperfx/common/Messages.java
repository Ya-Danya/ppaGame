package com.example.paperfx.common;

import java.util.List;

/**
 * Shared DTOs for the TCP JSON-lines protocol.
 * Java 17 compatible: simple POJOs + public fields for Jackson.
 */
public final class Messages {
    private Messages() {}

    public static final class Cell {
        public int x;
        public int y;

        public Cell(int x, int y) { this.x = x; this.y = y; }
        public Cell() {}
    }

    public static final class LeaderEntry {
        public String username;
        public int bestScore;

        public LeaderEntry(String username, int bestScore) {
            this.username = username;
            this.bestScore = bestScore;
        }
        public LeaderEntry() {}
    }

    public static final class Player {
        public String playerId;
        public int idx;
        public String username;
        public double x;
        public double y;
        public int score;
        public String color;
        public List<Cell> trail;

        public Player(String playerId, int idx, String username,
                      double x, double y, int score, String color, List<Cell> trail) {
            this.playerId = playerId;
            this.idx = idx;
            this.username = username;
            this.x = x;
            this.y = y;
            this.score = score;
            this.color = color;
            this.trail = trail;
        }
        public Player() {}
    }

    public static final class AuthOk {
        public final String type = "auth_ok";
        public String userId;
        public String username;
        public String playerId;
        public int idx;
        public String color;
        public int bestScore;

        public AuthOk(String userId, String username, String playerId, int idx, String color, int bestScore) {
            this.userId = userId;
            this.username = username;
            this.playerId = playerId;
            this.idx = idx;
            this.color = color;
            this.bestScore = bestScore;
        }
        public AuthOk() {}
    }

    public static final class State {
        public final String type = "state";
        public long tick;
        public String roomId;
        public int cellSize;
        public int gridW;
        public int gridH;

        /** Array length = gridW * gridH, owner index per cell. 0 = neutral. */
        public int[] owners;

        public List<Player> players;
        public List<LeaderEntry> leaderboard;

        public State(long tick, String roomId, int cellSize, int gridW, int gridH,
                     int[] owners, List<Player> players, List<LeaderEntry> leaderboard) {
            this.tick = tick;
            this.roomId = roomId;
            this.cellSize = cellSize;
            this.gridW = gridW;
            this.gridH = gridH;
            this.owners = owners;
            this.players = players;
            this.leaderboard = leaderboard;
        }
        public State() {}
    }
}
