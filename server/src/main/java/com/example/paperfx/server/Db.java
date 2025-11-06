package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        }
    }

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
                     "SELECT id, pass_salt, pass_hash, best_score FROM app_users WHERE username = ?"
             )) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return LoginResult.error("invalid username/password");
                UUID id = (UUID) rs.getObject("id");
                String salt = rs.getString("pass_salt");
                String hash = rs.getString("pass_hash");
                int bestScore = rs.getInt("best_score");

                String calc = PasswordUtil.pbkdf2Base64(password, salt);
                if (!PasswordUtil.slowEquals(hash, calc)) return LoginResult.error("invalid username/password");

                return LoginResult.ok(id.toString(), username, bestScore);
            }
        }
    }

    public void recordResult(String userId, int score) throws SQLException {
        UUID uid = UUID.fromString(userId);
        try (Connection c = get()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO game_results(user_id, score, played_at) VALUES (?, ?, ?)"
                )) {
                    ins.setObject(1, uid);
                    ins.setInt(2, score);
                    ins.setTimestamp(3, Timestamp.from(Instant.now()));
                    ins.executeUpdate();
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE app_users SET games_played = games_played + 1, best_score = GREATEST(best_score, ?) WHERE id = ?"
                )) {
                    upd.setInt(1, score);
                    upd.setObject(2, uid);
                    upd.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

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

    private static String normalize(String s) { return s == null ? "" : s.trim(); }

    public record RegisterResult(boolean ok, String userId, String username, int bestScore, String error) {
        public static RegisterResult ok(String id, String u, int best) { return new RegisterResult(true, id, u, best, null); }
        public static RegisterResult error(String msg) { return new RegisterResult(false, null, null, 0, msg); }
    }
    public record LoginResult(boolean ok, String userId, String username, int bestScore, String error) {
        public static LoginResult ok(String id, String u, int best) { return new LoginResult(true, id, u, best, null); }
        public static LoginResult error(String msg) { return new LoginResult(false, null, null, 0, msg); }
    }
}
