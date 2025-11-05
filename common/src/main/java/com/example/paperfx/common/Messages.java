package com.example.paperfx.common;

import java.util.List;

/** Shared DTOs for the TCP JSON-lines protocol. */
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
        public LeaderEntry(String username, int bestScore) { this.username = username; this.bestScore = bestScore; }
        public LeaderEntry() {}
    }

    // ---- client -> server ----
    public static final class Register {
        public final String type = "register";
        public String username;
        public String password;
        public Register(String username, String password) { this.username = username; this.password = password; }
        public Register() {}
    }

    public static final class Login {
        public final String type = "login";
        public String username;
        public String password;
        public Login(String username, String password) { this.username = username; this.password = password; }
        public Login() {}
    }

    public static final class Input {
        public final String type = "input";
        public int dx;
        public int dy;
        public Input(int dx, int dy) { this.dx = dx; this.dy = dy; }
        public Input() {}
    }

    public static final class CreateRoom {
        public final String type = "create_room";
        public String roomId;
        public CreateRoom(String roomId) { this.roomId = roomId; }
        public CreateRoom() {}
    }

    public static final class JoinRoom {
        public final String type = "join_room";
        public String roomId;
        public boolean spectator = false;
        public JoinRoom(String roomId) { this.roomId = roomId; }
        public JoinRoom() {}
    }

    public static final class ChatSend {

        public final String type = "chat_send";
        public String text;
        public ChatSend(String text) { this.text = text; }
        public ChatSend() {}
    }

    
    public static final class SetEmoji {
        public final String type = "set_emoji";
        public String emoji; // "" to clear
        public SetEmoji(String emoji) { this.emoji = emoji; }
        public SetEmoji() {}
    }

    public static final class GetProfile {
        public final String type = "get_profile";
        public GetProfile() {}
    }

    public static final class Profile {
        public final String type = "profile";
        public String username;
        public String selectedEmoji; // may be ""
        public java.util.List<String> unlockedEmojis;
        public long totalKills;
        public long totalScore;
        public int maxMatchScore;
        public int maxMatchKills;
        public int maxKillStreak;
        public Profile() {}
        public Profile(String username, String selectedEmoji, java.util.List<String> unlockedEmojis,
                       long totalKills, long totalScore, int maxMatchScore, int maxMatchKills, int maxKillStreak) {
            this.username = username;
            this.selectedEmoji = selectedEmoji;
            this.unlockedEmojis = unlockedEmojis;
            this.totalKills = totalKills;
            this.totalScore = totalScore;
            this.maxMatchScore = maxMatchScore;
            this.maxMatchKills = maxMatchKills;
            this.maxKillStreak = maxKillStreak;
        }
    }

    public static final class AchievementUnlocked {
        public final String type = "achievement_unlocked";
        public String code;
        public String title;
        public String emoji;
        public AchievementUnlocked() {}
        public AchievementUnlocked(String code, String title, String emoji) {
            this.code = code;
            this.title = title;
            this.emoji = emoji;
        }
    }

public static final class Ping { public final String type = "ping"; }

    // ---- server -> client ----
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

    public static final class Player {
        public String playerId;
        public int idx;
        public String username;
        public double x;
        public double y;
        public int score;
        public String color;
        public List<Cell> trail;

        public Player(String playerId, int idx, String username, double x, double y, int score, String color, List<Cell> trail) {
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

    public static final class State {
        public final String type = "state";
        public long tick;
        public String roomId;
        public int cellSize;
        public int gridW;
        public int gridH;
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
