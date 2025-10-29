package com.example.paperfx.client;

import com.example.paperfx.common.Messages;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.*;

public final class ClientMain extends Application {

    private NetworkClient client;

    private final Set<KeyCode> keysDown = new HashSet<>();
    private int lastDx = 0;
    private int lastDy = 0;

    @Override
    public void start(Stage stage) {
        stage.setTitle("PaperFX v0.6 — Auth + PostgreSQL + Leaderboard");
        stage.setScene(buildScene(stage));
        stage.setWidth(1180);
        stage.setHeight(820);
        stage.show();
    }

    private Scene buildScene(Stage stage) {
        TextField host = new TextField("127.0.0.1");
        TextField port = new TextField("7777");
        TextField username = new TextField("user1");
        PasswordField password = new PasswordField();
        password.setText("password");

        Label netStatus = new Label("not connected");
        Label authInfo = new Label("auth: -");
        Label err = new Label();
        err.setTextFill(Color.ORANGERED);

        Button connect = new Button("Connect");
        connect.setOnAction(e -> {
            connect.setDisable(true);
            try {
                client = new NetworkClient(host.getText().trim(), Integer.parseInt(port.getText().trim()));
                netStatus.setText("connected");
            } catch (Exception ex) {
                err.setText("connect failed: " + ex.getMessage());
                connect.setDisable(false);
            }
        });

        Button login = new Button("Login");
        login.setDefaultButton(true);
        login.setOnAction(e -> {
            if (client == null) { err.setText("connect first"); return; }
            client.send(new Messages.Login(username.getText().trim(), password.getText()));
        });

        Button register = new Button("Register");
        register.setOnAction(e -> {
            if (client == null) { err.setText("connect first"); return; }
            client.send(new Messages.Register(username.getText().trim(), password.getText()));
        });

        Button refreshLb = new Button("Refresh leaderboard");
        refreshLb.setOnAction(e -> {
            if (client == null) return;
            client.send(new Messages.LeaderboardReq(10));
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Host:"), host, connect, netStatus);
        form.addRow(1, new Label("Port:"), port);
        form.addRow(2, new Label("Username:"), username);
        form.addRow(3, new Label("Password:"), password);
        form.addRow(4, login, register);
        form.addRow(5, refreshLb);

        ListView<String> lbList = new ListView<>();
        lbList.setPrefHeight(240);

        Canvas canvas = new Canvas(800, 600);
        GraphicsContext g = canvas.getGraphicsContext2D();

        BorderPane root = new BorderPane();
        VBox left = new VBox(12,
                new Label("Login / Register"),
                form,
                authInfo,
                err,
                new Label("Leaderboard (best):"),
                lbList
        );
        left.setPadding(new Insets(12));
        left.setPrefWidth(340);

        root.setLeft(left);
        root.setCenter(new StackPane(canvas));

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> { keysDown.add(e.getCode()); updateInput(); });
        scene.setOnKeyReleased(e -> { keysDown.remove(e.getCode()); updateInput(); });

        new AnimationTimer() {
            @Override public void handle(long now) {
                if (client == null) {
                    renderNoState(g, "connect to server");
                    return;
                }
                Messages.AuthOk a = client.auth.get();
                if (a != null) authInfo.setText("auth: " + a.username + " (best=" + a.bestScore + ")");

                String lastErr = client.lastError.get();
                if (lastErr != null) err.setText(lastErr);

                Messages.State st = client.latestState.get();
                render(g, st, a == null ? null : a.playerId);

                if (st != null && st.leaderboard != null) {
                    lbList.getItems().setAll(formatLeaderboard(st.leaderboard));
                }
            }
        }.start();

        stage.setOnCloseRequest(e -> {
            if (client != null) {
                try { client.close(); } catch (IOException ignored) {}
            }
            Platform.exit();
        });

        return scene;
    }

    private List<String> formatLeaderboard(List<Messages.LeaderEntry> entries) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (Messages.LeaderEntry e : entries) {
            out.add(i + ". " + e.username + " — " + e.bestScore);
            i++;
        }
        return out;
    }

    private void updateInput() {
        if (client == null) return;
        if (client.auth.get() == null) return;

        int dx = 0, dy = 0;
        if (keysDown.contains(KeyCode.LEFT) || keysDown.contains(KeyCode.A)) dx -= 1;
        if (keysDown.contains(KeyCode.RIGHT) || keysDown.contains(KeyCode.D)) dx += 1;
        if (keysDown.contains(KeyCode.UP) || keysDown.contains(KeyCode.W)) dy -= 1;
        if (keysDown.contains(KeyCode.DOWN) || keysDown.contains(KeyCode.S)) dy += 1;

        if (dx != lastDx || dy != lastDy) {
            lastDx = dx; lastDy = dy;
            client.send(new Messages.Input(dx, dy));
        }
    }

    private void renderNoState(GraphicsContext g, String msg) {
        g.setFill(Color.rgb(18, 18, 22));
        g.fillRect(0, 0, 800, 600);
        g.setFill(Color.LIGHTGRAY);
        g.fillText(msg, 20, 30);
    }

    private void render(GraphicsContext g, Messages.State st, String myPlayerId) {
        g.setFill(Color.rgb(18, 18, 22));
        g.fillRect(0, 0, 800, 600);

        if (st == null) {
            g.setFill(Color.LIGHTGRAY);
            g.fillText("Waiting for state...", 20, 30);
            return;
        }

        Map<Integer, Color> idxToColor = new HashMap<>();
        if (st.players != null) {
            for (Messages.Player p : st.players) idxToColor.put(p.idx, parseHex(p.color, Color.CORNFLOWERBLUE));
        }

        if (st.owners != null) {
            int w = st.gridW, h = st.gridH, cell = st.cellSize;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int owner = st.owners[row + x];
                    if (owner == 0) continue;
                    g.setFill(idxToColor.getOrDefault(owner, Color.GRAY));
                    g.fillRect(x * cell, y * cell, cell, cell);
                }
            }
        }

        if (st.players != null) {
            for (Messages.Player p : st.players) {
                if (p.trail == null) continue;
                Color base = parseHex(p.color, Color.CORNFLOWERBLUE);
                g.setFill(new Color(base.getRed(), base.getGreen(), base.getBlue(), 0.55));
                for (Messages.Cell c : p.trail) g.fillRect(c.x * st.cellSize, c.y * st.cellSize, st.cellSize, st.cellSize);
            }
        }

        if (st.players != null) {
            for (Messages.Player p : st.players) {
                Color base = parseHex(p.color, Color.CORNFLOWERBLUE);
                double size = 16;

                g.setFill(Color.BLACK);
                g.fillRoundRect(p.x, p.y, size, size, 6, 6);

                g.setFill(base);
                g.fillRoundRect(p.x + 2, p.y + 2, size - 4, size - 4, 5, 5);

                if (p.playerId != null && p.playerId.equals(myPlayerId)) {
                    g.setStroke(Color.WHITE);
                    g.strokeRoundRect(p.x - 2, p.y - 2, size + 4, size + 4, 8, 8);
                }

                g.setFill(Color.WHITE);
                g.fillText(p.username + " (" + p.score + ")", p.x, p.y - 6);
            }
        }

        g.setFill(Color.rgb(255, 255, 255, 0.9));
        g.fillText("Match scoreboard:", 12, 18);
        double y = 38;
        if (st.players != null) {
            int limit = Math.min(10, st.players.size());
            for (int i = 0; i < limit; i++) {
                Messages.Player p = st.players.get(i);
                g.fillText((i + 1) + ". " + p.username + " — " + p.score, 12, y);
                y += 16;
            }
        }
    }

    private static Color parseHex(String hex, Color fallback) {
        if (hex == null) return fallback;
        try { return Color.web(hex); } catch (Exception e) { return fallback; }
    }

    public static void main(String[] args) { launch(args); }
}
