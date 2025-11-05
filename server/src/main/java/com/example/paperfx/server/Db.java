package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class Db {
    private final String url;
    private final String user;
    private final String pass;

    public Db(String url, String user, String pass) {
        this.url = url;
        this.user = user;
        this.pass = pass;
    }

    private Connection get() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    public void init() throws SQLException {
        try (Connection c = get(); Statement st = c.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS app_users(" +
                "  id UUID PRIMARY KEY," +
                "  username TEXT UNIQUE NOT NULL," +
                "  pass_salt TEXT NOT NULL," +
                "  pass_hash TEXT NOT NULL," +
                "  created_at TIMESTAMPTZ NOT NULL DEFAULT now()," +
                "  best_score INT NOT NULL DEFAULT 0," +
                "  games_played INT NOT NULL DEFAULT 0" +
                ");"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS game_results(" +
                "  id BIGSERIAL PRIMARY KEY," +
                "  user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE," +
                "  score INT NOT NULL," +
                "  played_at TIMESTAMPTZ NOT NULL DEFAULT now()" +
                ");"
            );

            st.execute(
                "CREATE TABLE IF NOT EXISTS achievements(" +
                "  id BIGSERIAL PRIMARY KEY," +
                "  user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE," +
                "  code TEXT NOT NULL," +
                "  unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now()," +
                "  UNIQUE(user_id, code)" +
                ");"
            );

            // ---- schema upgrades (idempotent) ----
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS selected_emoji TEXT NOT NULL DEFAULT '';");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS total_kills BIGINT NOT NULL DEFAULT 0;");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS total_score BIGINT NOT NULL DEFAULT 0;");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS max_match_score INT NOT NULL DEFAULT 0;");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS max_match_kills INT NOT NULL DEFAULT 0;");
            st.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS max_killstreak INT NOT NULL DEFAULT 0;");

            st.execute("ALTER TABLE game_results ADD COLUMN IF NOT EXISTS kills INT NOT NULL DEFAULT 0;");
            st.execute("ALTER TABLE game_results ADD COLUMN IF NOT EXISTS max_killstreak INT NOT NULL DEFAULT 0;");
        }
    }

    // ---------------- auth ----------------

    public RegisterResult register(String username, String password) throws SQLException {
        username = normalize(username);
        if (username.length() < 3 || username.length() > 24) return RegisterResult.error("username must be 3..24 chars");
        if (!username.matches("[a-zA-Z0-9_\\-]+")) return RegisterResult.error("username allowed: [a-zA-Z0-9_-]");
        if (password == null || password.length() < 6) return RegisterResult.error("password must be >= 6 chars");

        UUID id = UUID.randomUUID();
        String salt = PasswordUtil.newSaltBase64();
        String hash = PasswordUtil.pbkdf2Base64(password, salt);

        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_users(id, username, pass_salt, pass_hash) VALUES (?, ?, ?, ?)"
             )) {
            ps.setObject(1, id);
            ps.setString(2, username);
            ps.setString(3, salt);
            ps.setString(4, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("duplicate") || m.contains("unique")) return RegisterResult.error("username already exists");
            throw e;
        }

        return RegisterResult.ok(id.toString(), username, 0);
    }

    public LoginResult login(String username, String password) throws SQLException {
        username = normalize(username);
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, pass_salt, pass_hash, best_score, selected_emoji FROM app_users WHERE username = ?"
             )) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return LoginResult.error("invalid username/password");
                UUID id = (UUID) rs.getObject("id");
                String salt = rs.getString("pass_salt");
                String hash = rs.getString("pass_hash");
                int bestScore = rs.getInt("best_score");
                String selectedEmoji = rs.getString("selected_emoji");

                String calc = PasswordUtil.pbkdf2Base64(password, salt);
                if (!PasswordUtil.slowEquals(hash, calc)) return LoginResult.error("invalid username/password");

                return LoginResult.ok(id.toString(), username, bestScore, selectedEmoji == null ? "" : selectedEmoji);
            }
        }
    }

    // ---------------- profile & emoji ----------------

    public Profile getProfile(String userId) throws SQLException {
        UUID uid = UUID.fromString(userId);
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT username, selected_emoji, total_kills, total_score, max_match_score, max_match_kills, max_killstreak " +
                     "FROM app_users WHERE id = ?"
             )) {
            ps.setObject(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String username = rs.getString("username");
                String selected = rs.getString("selected_emoji");
                long totalKills = rs.getLong("total_kills");
                long totalScore = rs.getLong("total_score");
                int maxMatchScore = rs.getInt("max_match_score");
                int maxMatchKills = rs.getInt("max_match_kills");
                int maxKillStreak = rs.getInt("max_killstreak");

                Set<String> codes = getAchievementCodes(c, uid);
                List<String> emojis = unlockedEmojisFromCodes(codes);
                // selected emoji must be one of unlocked; otherwise clear
                if (selected == null) selected = "";
                if (!selected.isBlank() && !emojis.contains(selected)) selected = "";
                return new Profile(username, selected, emojis, totalKills, totalScore, maxMatchScore, maxMatchKills, maxKillStreak);
            }
        }
    }

    public boolean setEmoji(String userId, String emoji) throws SQLException {
        UUID uid = UUID.fromString(userId);
        if (emoji == null) emoji = "";
        emoji = emoji.trim();
        if (emoji.length() > 8) emoji = ""; // simple guard

        Profile prof = getProfile(userId);
        if (prof == null) return false;
        if (!emoji.isBlank() && (prof.unlockedEmojis == null || !prof.unlockedEmojis.contains(emoji))) {
            return false;
        }

        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement("UPDATE app_users SET selected_emoji = ? WHERE id = ?")) {
            ps.setString(1, emoji);
            ps.setObject(2, uid);
            ps.executeUpdate();
        }
        return true;
    }

    // ---------------- stats update ----------------

    /** Increment total kills immediately (for achievements based on total kills). */
    public List<AchievementUnlock> addKills(String userId, int deltaKills) throws SQLException {
        if (deltaKills <= 0) return List.of();
        UUID uid = UUID.fromString(userId);

        try (Connection c = get()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE app_users SET total_kills = total_kills + ? WHERE id = ?"
                )) {
                    upd.setInt(1, deltaKills);
                    upd.setObject(2, uid);
                    upd.executeUpdate();
                }

                Profile p = readProfile(c, uid);
                Set<String> existing = getAchievementCodes(c, uid);
                List<AchievementUnlock> unlocked = unlockNewAchievements(c, uid, p, existing);

                c.commit();
                return unlocked;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Record end-of-life(match) result: updates totals, maxima and unlocks achievements. */
    public List<AchievementUnlock> recordResult(String userId, int score, int matchKills, int matchKillStreakMax) throws SQLException {
        UUID uid = UUID.fromString(userId);
        score = Math.max(0, score);
        matchKills = Math.max(0, matchKills);
        matchKillStreakMax = Math.max(0, matchKillStreakMax);

        try (Connection c = get()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO game_results(user_id, score, kills, max_killstreak, played_at) VALUES (?, ?, ?, ?, ?)"
                )) {
                    ins.setObject(1, uid);
                    ins.setInt(2, score);
                    ins.setInt(3, matchKills);
                    ins.setInt(4, matchKillStreakMax);
                    ins.setTimestamp(5, Timestamp.from(Instant.now()));
                    ins.executeUpdate();
                }

                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE app_users SET " +
                        "  games_played = games_played + 1," +
                        "  best_score = GREATEST(best_score, ?)," +
                        "  total_score = total_score + ?," +
                        "  max_match_score = GREATEST(max_match_score, ?)," +
                        "  max_match_kills = GREATEST(max_match_kills, ?)," +
                        "  max_killstreak = GREATEST(max_killstreak, ?)" +
                        "WHERE id = ?"
                )) {
                    upd.setInt(1, score);
                    upd.setLong(2, score);
                    upd.setInt(3, score);
                    upd.setInt(4, matchKills);
                    upd.setInt(5, matchKillStreakMax);
                    upd.setObject(6, uid);
                    upd.executeUpdate();
                }

                Profile p = readProfile(c, uid);
                Set<String> existing = getAchievementCodes(c, uid);
                List<AchievementUnlock> unlocked = unlockNewAchievements(c, uid, p, existing);

                c.commit();
                return unlocked;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ---------------- leaderboard (global) ----------------

    public List<Messages.LeaderEntry> topBest(int limit) throws SQLException {
        limit = Math.max(1, Math.min(limit, 50));
        List<Messages.LeaderEntry> out = new ArrayList<>();
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT username, best_score FROM app_users ORDER BY best_score DESC, created_at ASC LIMIT ?"
             )) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Messages.LeaderEntry(rs.getString("username"), rs.getInt("best_score")));
            }
        }
        return out;
    }

    // ---------------- achievements ----------------

    public record AchievementDef(String code, String title, String emoji) {}
    public record AchievementUnlock(String code, String title, String emoji) {}
    public record Profile(String username, String selectedEmoji, List<String> unlockedEmojis,
                          long totalKills, long totalScore, int maxMatchScore, int maxMatchKills, int maxKillStreak) {}

    private static final String[] LEVELS = new String[] {"Новичок", "Мастер", "Гений"};

    private static final AchievementDef[] DEFS = new AchievementDef[] {
            // Total kills
            new AchievementDef("TOTAL_KILLS_1", "Убийства — " + LEVELS[0], "🔪"),
            new AchievementDef("TOTAL_KILLS_2", "Убийства — " + LEVELS[1], "🗡️"),
            new AchievementDef("TOTAL_KILLS_3", "Убийства — " + LEVELS[2], "☠️"),

            // Total score
            new AchievementDef("TOTAL_SCORE_1", "Очки — " + LEVELS[0], "🟩"),
            new AchievementDef("TOTAL_SCORE_2", "Очки — " + LEVELS[1], "🏆"),
            new AchievementDef("TOTAL_SCORE_3", "Очки — " + LEVELS[2], "👑"),

            // Max match score
            new AchievementDef("MAX_MATCH_SCORE_1", "Очки за матч — " + LEVELS[0], "⭐"),
            new AchievementDef("MAX_MATCH_SCORE_2", "Очки за матч — " + LEVELS[1], "🌟"),
            new AchievementDef("MAX_MATCH_SCORE_3", "Очки за матч — " + LEVELS[2], "💫"),

            // Max match kills
            new AchievementDef("MAX_MATCH_KILLS_1", "Убийства за матч — " + LEVELS[0], "💥"),
            new AchievementDef("MAX_MATCH_KILLS_2", "Убийства за матч — " + LEVELS[1], "🔥"),
            new AchievementDef("MAX_MATCH_KILLS_3", "Убийства за матч — " + LEVELS[2], "⚡"),

            // Max killstreak
            new AchievementDef("MAX_KILLSTREAK_1", "Серия убийств — " + LEVELS[0], "🎯"),
            new AchievementDef("MAX_KILLSTREAK_2", "Серия убийств — " + LEVELS[1], "🧨"),
            new AchievementDef("MAX_KILLSTREAK_3", "Серия убийств — " + LEVELS[2], "🚀"),
    };

    private static final long[] TH_TOTAL_KILLS = new long[] {10, 50, 200};
    private static final long[] TH_TOTAL_SCORE = new long[] {1_000, 10_000, 50_000};
    private static final int[] TH_MAX_MATCH_SCORE = new int[] {200, 1_000, 3_000};
    private static final int[] TH_MAX_MATCH_KILLS = new int[] {3, 10, 25};
    private static final int[] TH_MAX_KILLSTREAK = new int[] {3, 10, 20};

    private static Profile readProfile(Connection c, UUID uid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT username, selected_emoji, total_kills, total_score, max_match_score, max_match_kills, max_killstreak " +
                "FROM app_users WHERE id = ?"
        )) {
            ps.setObject(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Profile(
                        rs.getString("username"),
                        nz(rs.getString("selected_emoji")),
                        List.of(),
                        rs.getLong("total_kills"),
                        rs.getLong("total_score"),
                        rs.getInt("max_match_score"),
                        rs.getInt("max_match_kills"),
                        rs.getInt("max_killstreak")
                );
            }
        }
    }

    private static Set<String> getAchievementCodes(Connection c, UUID uid) throws SQLException {
        HashSet<String> s = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT code FROM achievements WHERE user_id = ?")) {
            ps.setObject(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) s.add(rs.getString("code"));
            }
        }
        return s;
    }

    private static List<String> unlockedEmojisFromCodes(Set<String> codes) {
        LinkedHashSet<String> emojis = new LinkedHashSet<>();
        for (AchievementDef d : DEFS) {
            if (codes.contains(d.code)) emojis.add(d.emoji);
        }
        return new ArrayList<>(emojis);
    }

    private static List<AchievementUnlock> unlockNewAchievements(Connection c, UUID uid, Profile p, Set<String> existing) throws SQLException {
        if (p == null) return List.of();
        ArrayList<AchievementUnlock> unlocked = new ArrayList<>();

        // helper
        class Check {
            void maybe(String code, long have, long need) { if (have >= need && !existing.contains(code)) add(code); }
            void maybeI(String code, int have, int need) { if (have >= need && !existing.contains(code)) add(code); }
            void add(String code) {
                AchievementDef d = defByCode(code);
                if (d == null) return;
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO achievements(user_id, code, unlocked_at) VALUES (?, ?, now())")) {
                    ins.setObject(1, uid);
                    ins.setString(2, d.code);
                    ins.executeUpdate();
                } catch (SQLException e) {
                    // ignore duplicates due to races
                }
                existing.add(d.code);
                unlocked.add(new AchievementUnlock(d.code, d.title, d.emoji));
            }
        }
        Check chk = new Check();

        // total kills
        chk.maybe("TOTAL_KILLS_1", p.totalKills, TH_TOTAL_KILLS[0]);
        chk.maybe("TOTAL_KILLS_2", p.totalKills, TH_TOTAL_KILLS[1]);
        chk.maybe("TOTAL_KILLS_3", p.totalKills, TH_TOTAL_KILLS[2]);

        // total score
        chk.maybe("TOTAL_SCORE_1", p.totalScore, TH_TOTAL_SCORE[0]);
        chk.maybe("TOTAL_SCORE_2", p.totalScore, TH_TOTAL_SCORE[1]);
        chk.maybe("TOTAL_SCORE_3", p.totalScore, TH_TOTAL_SCORE[2]);

        // max match score
        chk.maybeI("MAX_MATCH_SCORE_1", p.maxMatchScore, TH_MAX_MATCH_SCORE[0]);
        chk.maybeI("MAX_MATCH_SCORE_2", p.maxMatchScore, TH_MAX_MATCH_SCORE[1]);
        chk.maybeI("MAX_MATCH_SCORE_3", p.maxMatchScore, TH_MAX_MATCH_SCORE[2]);

        // max match kills
        chk.maybeI("MAX_MATCH_KILLS_1", p.maxMatchKills, TH_MAX_MATCH_KILLS[0]);
        chk.maybeI("MAX_MATCH_KILLS_2", p.maxMatchKills, TH_MAX_MATCH_KILLS[1]);
        chk.maybeI("MAX_MATCH_KILLS_3", p.maxMatchKills, TH_MAX_MATCH_KILLS[2]);

        // killstreak
        chk.maybeI("MAX_KILLSTREAK_1", p.maxKillStreak, TH_MAX_KILLSTREAK[0]);
        chk.maybeI("MAX_KILLSTREAK_2", p.maxKillStreak, TH_MAX_KILLSTREAK[1]);
        chk.maybeI("MAX_KILLSTREAK_3", p.maxKillStreak, TH_MAX_KILLSTREAK[2]);

        return unlocked;
    }

    private static AchievementDef defByCode(String code) {
        for (AchievementDef d : DEFS) if (d.code.equals(code)) return d;
        return null;
    }

    private static String normalize(String s) { return s == null ? "" : s.trim(); }
    private static String nz(String s) { return s == null ? "" : s; }

    public record RegisterResult(boolean ok, String userId, String username, int bestScore, String error) {
        public static RegisterResult ok(String id, String u, int best) { return new RegisterResult(true, id, u, best, null); }
        public static RegisterResult error(String msg) { return new RegisterResult(false, null, null, 0, msg); }
    }
    public record LoginResult(boolean ok, String userId, String username, int bestScore, String selectedEmoji, String error) {
        public static LoginResult ok(String id, String u, int best, String selectedEmoji) { return new LoginResult(true, id, u, best, selectedEmoji, null); }
        public static LoginResult error(String msg) { return new LoginResult(false, null, null, 0, "", msg); }
    }
}
