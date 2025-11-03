package com.example.paperfx.client;

import com.example.paperfx.common.Messages;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.*;

public final class ClientMain extends Application {
    private static final int PORT = 7777;

    private NetClient net;
    private volatile boolean authed = false;
    private volatile boolean inRoom = false;

    private ListView<String> leaderboardView;
    private ListView<String> chatView;
    private volatile long lastChatAtMs = 0;

    private final ScheduledExecutorService pingExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ping");
        t.setDaemon(true);
        return t;
    });

    private final Object stateLock = new Object();
    private Messages.State lastState = null;

    private final SimpleStringProperty statusText = new SimpleStringProperty("Not connected");

    private int curDx = 0;
    private int curDy = 0;

    @Override public void start(Stage stage) throws Exception {
        net = new NetClient("127.0.0.1", PORT);
        net.setOnMessage((type, node) -> Platform.runLater(() -> onMessage(type, node)));
        net.setOnClose(() -> Platform.runLater(() -> {
            statusText.set("Disconnected");
            authed = false;
            inRoom = false;
        }));

        try {
            net.connect();
            statusText.set("Connected to server");
        } catch (Exception e) {
            statusText.set("Connect failed: " + e.getMessage());
        }

        // --- Auth UI ---
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        usernameField.setText("");

        PasswordField passField = new PasswordField();
        passField.setPromptText("Password");
        passField.setText("");

        TextField passVisible = new TextField();
        passVisible.setPromptText("Password");
        passVisible.setManaged(false);
        passVisible.setVisible(false);

        // bind text between password and visible field
        passVisible.textProperty().bindBidirectional(passField.textProperty());

        Button showBtn = new Button("👁");
        showBtn.setFocusTraversable(false);
        showBtn.setOnAction(e -> {
            boolean showing = passVisible.isVisible();
            passVisible.setVisible(!showing);
            passVisible.setManaged(!showing);
            passField.setVisible(showing);
            passField.setManaged(showing);
        });

        HBox passRow = new HBox(6, new StackPane(passField, passVisible), showBtn);
        passRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(passRow.getChildren().get(0), Priority.ALWAYS);

        Button loginBtn = new Button("Login");
        Button regBtn = new Button("Register");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        regBtn.setMaxWidth(Double.MAX_VALUE);

        Label status = new Label();
        status.textProperty().bind(statusText);

        VBox authBox = new VBox(8, new Label("Account"), usernameField, passRow, loginBtn, regBtn, status);
        authBox.setPadding(new Insets(10));
        authBox.setPrefWidth(260);

        // --- Room UI ---
        TextField roomIdField = new TextField();
        roomIdField.setPromptText("Room ID (e.g. MAIN)");
        roomIdField.setDisable(true);

        Button createRoomBtn = new Button("Create room");
        Button joinRoomBtn = new Button("Join room");
        createRoomBtn.setMaxWidth(Double.MAX_VALUE);
        joinRoomBtn.setMaxWidth(Double.MAX_VALUE);
        createRoomBtn.setDisable(true);
        joinRoomBtn.setDisable(true);

        Label roomInfo = new Label("Room: -");

        VBox roomBox = new VBox(8, new Label("Rooms"), roomInfo, roomIdField, createRoomBtn, joinRoomBtn);
        roomBox.setPadding(new Insets(10));

        // --- Leaderboard ---
        ListView<String> leaderboard = new ListView<>();
        leaderboard.setPrefHeight(180);

        // --- Chat ---
        ListView<String> chatList = new ListView<>();
        chatList.setPrefHeight(220);

        this.leaderboardView = leaderboard;
        this.chatView = chatList;

        VBox left = new VBox(10, authBox, roomBox, new Label("Leaderboard (room)"), leaderboard, new Label("Chat"), chatList);
        left.setPadding(new Insets(6));
        left.setPrefWidth(280);

        // --- Canvas ---
        Canvas canvas = new Canvas(800, 600);
        GraphicsContext g = canvas.getGraphicsContext2D();
        canvas.setFocusTraversable(true);

        BorderPane root = new BorderPane();
        root.setLeft(left);
        root.setCenter(new StackPane(canvas));

        Scene scene = new Scene(root, 1100, 700);
        stage.setTitle("PaperFX (Rooms)");
        stage.setScene(scene);
        stage.show();

        // input handlers
        scene.setOnKeyPressed(e -> {
            if (!inRoom) return;
            KeyCode k = e.getCode();
            if (k == KeyCode.W || k == KeyCode.UP) { curDx = 0; curDy = -1; sendInput(); }
            else if (k == KeyCode.S || k == KeyCode.DOWN) { curDx = 0; curDy = 1; sendInput(); }
            else if (k == KeyCode.A || k == KeyCode.LEFT) { curDx = -1; curDy = 0; sendInput(); }
            else if (k == KeyCode.D || k == KeyCode.RIGHT) { curDx = 1; curDy = 0; sendInput(); }
            else if (k == KeyCode.Q) { sendChat("Всем привет"); }
            else if (k == KeyCode.E) { sendChat("Вхавха"); }
            else if (k == KeyCode.R) { sendChat("Рачки))"); }
        });


        // focus/idle: reset input on focus loss
        stage.focusedProperty().addListener((obs, oldV, newV) -> {
            if (!newV) {
                curDx = 0; curDy = 0;
                sendInput();
            } else {
                canvas.requestFocus();
            }
        });

        // auth actions (prevent multi-click)
        loginBtn.setOnAction(e -> {
            if (authed) return;
            loginBtn.setDisable(true);
            regBtn.setDisable(true);
            net.send(new Messages.Login(usernameField.getText(), passField.getText()));
        });
        regBtn.setOnAction(e -> {
            if (authed) return;
            loginBtn.setDisable(true);
            regBtn.setDisable(true);
            net.send(new Messages.Register(usernameField.getText(), passField.getText()));
        });

        createRoomBtn.setOnAction(e -> net.send(new Messages.CreateRoom()));
        joinRoomBtn.setOnAction(e -> net.send(new Messages.JoinRoom(roomIdField.getText().trim())));

        // ping keepalive
        pingExec.scheduleAtFixedRate(() -> {
            if (net != null) net.send(new Messages.Ping(System.currentTimeMillis()));
        }, 2, 5, TimeUnit.SECONDS);

        // render loop
        new AnimationTimer() {
            @Override public void handle(long now) {
                Messages.State s;
                synchronized (stateLock) { s = lastState; }
                if (s == null || s.owners == null) {
                    g.setFill(Color.BLACK);
                    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                    return;
                }
                render(g, canvas, s);
            }
        }.start();

        // initial focus
        Platform.runLater(canvas::requestFocus);
    }

    private void sendInput() {
        if (net == null) return;
        if (!inRoom) return;
        net.send(new Messages.Input(curDx, curDy));
    }

    private void onMessage(String type, JsonNode n) {
        switch (type) {
            case "auth_ok" -> {
                authed = true;
                statusText.set("Authenticated as " + n.path("username").asText());
                // enable room controls
                // (we can't access UI controls directly here; we use status + infer from scene graph)
                enableRoomControls(true);
                // auto-join MAIN for convenience
                net.send(new Messages.JoinRoom("MAIN"));
            }
            case "room_joined" -> {
                inRoom = true;
                if (chatView != null) Platform.runLater(() -> chatView.getItems().clear());
                String roomId = n.path("roomId").asText("-");
                int cap = n.path("capacity").asInt(4);
                int players = n.path("players").asInt(0);
                statusText.set("Joined room " + roomId + " (" + players + "/" + cap + ")");
                updateRoomInfo(roomId, players, cap);
            }
            case "state" -> {
                Messages.State s = new Messages.State();
                s.tick = n.path("tick").asLong(0);
                s.roomId = n.path("roomId").asText(null);
                s.cellSize = n.path("cellSize").asInt(10);
                s.gridW = n.path("gridW").asInt(0);
                s.gridH = n.path("gridH").asInt(0);

                // owners array
                if (n.hasNonNull("owners")) {
                    int len = n.get("owners").size();
                    int[] owners = new int[len];
                    for (int i = 0; i < len; i++) owners[i] = n.get("owners").get(i).asInt(0);
                    s.owners = owners;
                }

                // players
                if (n.hasNonNull("players")) {
                    List<Messages.Player> ps = new ArrayList<>();
                    for (JsonNode pj : n.get("players")) {
                        Messages.Player p = new Messages.Player();
                        p.playerId = pj.path("playerId").asText();
                        p.idx = pj.path("idx").asInt();
                        p.username = pj.path("username").asText();
                        p.x = pj.path("x").asDouble();
                        p.y = pj.path("y").asDouble();
                        p.score = pj.path("score").asInt();
                        p.color = pj.path("color").asText("#ffffff");
                        // trail
                        List<Messages.Cell> trail = new ArrayList<>();
                        if (pj.hasNonNull("trail")) {
                            for (JsonNode cj : pj.get("trail")) {
                                trail.add(new Messages.Cell(cj.path("x").asInt(), cj.path("y").asInt()));
                            }
                        }
                        p.trail = trail;
                        ps.add(p);
                    }
                    s.players = ps;
                } else {
                    s.players = List.of();
                }

                // leaderboard
                if (n.hasNonNull("leaderboard")) {
                    List<String> lb = new ArrayList<>();
                    for (JsonNode ej : n.get("leaderboard")) {
                        String u = ej.path("username").asText();
                        int sc = ej.path("bestScore").asInt();
                        lb.add(u + " — " + sc);
                    }
                    updateLeaderboard(lb);
                }

                synchronized (stateLock) { lastState = s; }
            }
            case "chat_msg" -> {
                String ru = n.path("username").asText("");
                String text = n.path("text").asText("");
                appendChatLine(ru + ": " + text);
            }
            case "error" -> {
                String reason = n.path("reason").asText("error");
                statusText.set("Error: " + reason);
                if ("room_full".equals(reason)) {
                    // stay in lobby
                }
                // re-enable auth buttons if not authed
                if (!authed) enableAuthButtons(true);
            }
            default -> {}
        }
    }

    // We find controls via scene lookup to avoid storing references (keeps example compact).
    private void enableAuthButtons(boolean enabled) {
        Scene sc = getPrimaryScene();
        if (sc == null) return;
        for (javafx.scene.Node node : sc.getRoot().lookupAll(".button")) {
            if (node instanceof Button b) {
                if ("Login".equals(b.getText()) || "Register".equals(b.getText())) {
                    b.setDisable(!enabled);
                }
            }
        }
    }

    private void enableRoomControls(boolean enabled) {
        Scene sc = getPrimaryScene();
        if (sc == null) return;
        for (javafx.scene.Node node : sc.getRoot().lookupAll(".text-field")) {
            if (node instanceof TextField tf && "Room ID (e.g. MAIN)".equals(tf.getPromptText())) {
                tf.setDisable(!enabled);
            }
        }
        for (javafx.scene.Node node : sc.getRoot().lookupAll(".button")) {
            if (node instanceof Button b) {
                if ("Create room".equals(b.getText()) || "Join room".equals(b.getText())) {
                    b.setDisable(!enabled);
                }
                if ("Login".equals(b.getText()) || "Register".equals(b.getText())) {
                    b.setDisable(true);
                }
            }
        }
    }

    private void updateRoomInfo(String roomId, int players, int cap) {
        Scene sc = getPrimaryScene();
        if (sc == null) return;
        for (javafx.scene.Node node : sc.getRoot().lookupAll(".label")) {
            if (node instanceof Label l && l.getText().startsWith("Room:")) {
                l.setText("Room: " + roomId + " (" + players + "/" + cap + ")");
            }
        }
    }

    private void updateLeaderboard(List<String> entries) {
        if (leaderboardView == null) return;
        Platform.runLater(() -> leaderboardView.getItems().setAll(entries));
    }

    private void appendChatLine(String line) {
        if (chatView == null) return;
        Platform.runLater(() -> {
            var items = chatView.getItems();
            items.add(line);
            while (items.size() > 60) items.remove(0);
            chatView.scrollTo(items.size() - 1);
        });
    }

    private void sendChat(String text) {
        if (!inRoom || !authed) return;
        long now = System.currentTimeMillis();
        if (now - lastChatAtMs < 5000) return; // local throttle (server also enforces)
        lastChatAtMs = now;
        net.send(new Messages.ChatSend(text));
    }


    private Scene getPrimaryScene() {
        // hacky but works for a single-stage app
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null) return s.getScene();
        }
        return null;
    }

    private void render(GraphicsContext g, Canvas canvas, Messages.State s) {
        int cs = s.cellSize <= 0 ? 10 : s.cellSize;
        int w = s.gridW * cs;
        int h = s.gridH * cs;

        // resize canvas to fit grid (optional)
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }

        // background
        g.setFill(Color.rgb(15, 15, 20));
        g.fillRect(0, 0, w, h);

        // build color map by idx from players list
        Map<Integer, Color> colorByIdx = new HashMap<>();
        for (Messages.Player p : s.players) {
            try { colorByIdx.put(p.idx, Color.web(p.color)); }
            catch (Exception ignored) { colorByIdx.put(p.idx, Color.WHITE); }
        }

        // draw territory
        for (int y = 0; y < s.gridH; y++) {
            for (int x = 0; x < s.gridW; x++) {
                int o = s.owners[y * s.gridW + x];
                if (o == 0) continue;
                Color c = colorByIdx.getOrDefault(o, Color.GRAY);
                g.setFill(c);
                g.fillRect(x * cs, y * cs, cs, cs);
            }
        }

        // draw trails semi-transparent
        for (Messages.Player p : s.players) {
            Color base = colorByIdx.getOrDefault(p.idx, Color.WHITE);
            Color t = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0.45);
            g.setFill(t);
            if (p.trail != null) {
                for (Messages.Cell c : p.trail) {
                    g.fillRect(c.x * cs, c.y * cs, cs, cs);
                }
            }
        }

        // draw players
        for (Messages.Player p : s.players) {
            Color c = colorByIdx.getOrDefault(p.idx, Color.WHITE);
            g.setFill(c.brighter());
            g.fillOval(p.x * cs, p.y * cs, cs, cs);
            g.setFill(Color.WHITE);
            g.fillText(p.username + " (" + p.score + ")", p.x * cs + 2, p.y * cs - 2);
        }
    }

    @Override public void stop() {
        try { pingExec.shutdownNow(); } catch (Exception ignored) {}
        try { if (net != null) net.close(); } catch (Exception ignored) {}
    }
}
