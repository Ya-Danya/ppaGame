package com.example.paperfx.client;

import com.example.paperfx.common.Messages;
import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperFxApp extends Application {

    // ---- network ----
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();

    // ---- ui ----
    private Stage stage;
    private Scene loginScene;
    private Scene gameScene;

    private TextField tfUser;
    private PasswordField pfPass;
    private TextField tfPassVisible;
    private CheckBox cbShowPass;
    private Label lblLoginStatus;

    private Canvas canvas;
    private ListView<String> chatView;
    private ObservableList<String> chatItems = FXCollections.observableArrayList();
    private TextField chatInput;
    private Label roomLabel;
    private TableView<LeaderRow> leaderboard;

    private TextField roomIdField;
    private Button btnSpectatePlay;

    // profile UI
    private HBox profileWidget;
    private StackPane profileOverlay;
    private VBox profileCard;
    private Label profileTitle;
    private Label profileNameLabel;
    private Label profileStats;
    private ListView<String> profileAchievements;

    private volatile boolean isSpectator = false;

    // ---- state ----
    private volatile Messages.State lastState;
    private volatile String myUsername = "";
    private volatile String currentRoomId = "MAIN";

    private final AtomicBoolean running = new AtomicBoolean(false);

    // input handling (prevents "stuck" when focus lost)
    private final EnumSet<KeyCode> pressed = EnumSet.noneOf(KeyCode.class);
    private int desiredDx = 0;
    private int desiredDy = 0;
    private long lastInputSentNs = 0;

    // when false, ignore movement keys (e.g., when typing in chat)
    private boolean gameControlEnabled = true;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("PaperFX");

        buildLoginScene();
        buildGameScene();

        stage.setScene(loginScene);
        stage.setWidth(1100);
        stage.setHeight(760);
        stage.show();
    }

    private void buildLoginScene() {
        tfUser = new TextField();
        tfUser.setPromptText("Username");

        pfPass = new PasswordField();
        pfPass.setPromptText("Password");

        tfPassVisible = new TextField();
        tfPassVisible.setPromptText("Password");
        tfPassVisible.setManaged(false);
        tfPassVisible.setVisible(false);

        cbShowPass = new CheckBox("show");
        cbShowPass.selectedProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                tfPassVisible.setText(pfPass.getText());
                tfPassVisible.setManaged(true);
                tfPassVisible.setVisible(true);
                pfPass.setManaged(false);
                pfPass.setVisible(false);
            } else {
                pfPass.setText(tfPassVisible.getText());
                pfPass.setManaged(true);
                pfPass.setVisible(true);
                tfPassVisible.setManaged(false);
                tfPassVisible.setVisible(false);
            }
        });

        // keep in sync
        pfPass.textProperty().addListener((o, a, b) -> { if (!cbShowPass.isSelected()) return; tfPassVisible.setText(b); });
        tfPassVisible.textProperty().addListener((o, a, b) -> { if (cbShowPass.isSelected()) pfPass.setText(b); });

        Button btnLogin = new Button("Login");
        Button btnRegister = new Button("Register");

        lblLoginStatus = new Label();

        btnLogin.setOnAction(e -> doAuth("login"));
        btnRegister.setOnAction(e -> doAuth("register"));

        HBox passRow = new HBox(8, pfPass, tfPassVisible, cbShowPass);
        passRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(12,
                new Label("Server: localhost:7777"),
                tfUser,
                passRow,
                new HBox(10, btnLogin, btnRegister),
                lblLoginStatus
        );
        ((HBox)box.getChildren().get(3)).setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20));
        box.setMaxWidth(360);

        BorderPane root = new BorderPane();
        root.setCenter(box);

        // Dark theme base
        root.setStyle("-fx-background-color: #141414; -fx-text-fill: #e6e6e6;");
        tfUser.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6; -fx-prompt-text-fill: #777;");
        pfPass.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6; -fx-prompt-text-fill: #777;");
        tfPassVisible.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6; -fx-prompt-text-fill: #777;");
        lblLoginStatus.setStyle("-fx-text-fill: #f0f0f0;");

        loginScene = new Scene(root);
        loginScene.setFill(Color.web("#141414"));
    }

    private void buildGameScene() {
        canvas = new Canvas(800, 600);
        canvas.setFocusTraversable(true); // allow keyboard focus
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus(); // click on game field returns focus
            if (!isSpectator) gameControlEnabled = true; // spectators can't regain control
        });

        roomLabel = new Label("Room: MAIN");
        Label help = new Label("Move: WASD / Arrows | Chat: Q/E/R quick msgs, or type + Enter");

        // Profile widget (top-right): circle + username, opens profile card
        Circle avatar = new Circle(10);
        avatar.setFill(Color.web("#444"));
        avatar.setStroke(Color.web("#777"));
        profileNameLabel = new Label(myUsername == null ? "" : myUsername);
        profileNameLabel.setStyle("-fx-text-fill: #e6e6e6; -fx-font-weight: 600;");
        profileWidget = new HBox(8, avatar, profileNameLabel);
        profileWidget.setAlignment(Pos.CENTER_RIGHT);
        profileWidget.setPadding(new Insets(6, 10, 6, 10));
        profileWidget.setStyle("-fx-background-color: #1f1f1f; -fx-background-radius: 10;");
        profileWidget.setOnMouseClicked(e -> toggleProfile());

        // Profile overlay card (initially hidden)
        profileTitle = new Label("Profile");
        profileTitle.setStyle("-fx-text-fill: #f0f0f0; -fx-font-size: 16px; -fx-font-weight: 700;");
        profileStats = new Label("Loading...");
        profileStats.setStyle("-fx-text-fill: #d6d6d6; -fx-font-size: 13px;");
        profileAchievements = new ListView<>();
        profileAchievements.setPrefHeight(180);

        Button btnCloseProfile = new Button("Close");
        btnCloseProfile.setOnAction(e -> toggleProfile());

        profileCard = new VBox(10,
                profileTitle,
                profileStats,
                new Label("Achievements"),
                profileAchievements,
                btnCloseProfile
        );
        profileCard.setPadding(new Insets(14));
        profileCard.setMaxWidth(320);
        profileCard.setStyle("-fx-background-color: #202020; -fx-background-radius: 14; -fx-border-color: #333; -fx-border-radius: 14;");

        profileOverlay = new StackPane(profileCard);
        profileOverlay.setAlignment(Pos.TOP_RIGHT);
        profileOverlay.setPadding(new Insets(10));
        profileOverlay.setPickOnBounds(true);
        profileOverlay.setVisible(false);
        profileOverlay.setManaged(false);

        // leaderboard table
        leaderboard = new TableView<>();
        leaderboard.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        TableColumn<LeaderRow, String> colName = new TableColumn<>("User");
        colName.setCellValueFactory(c -> c.getValue().usernameProperty());
        TableColumn<LeaderRow, Number> colScore = new TableColumn<>("Score");
        colScore.setCellValueFactory(c -> c.getValue().scoreProperty());
        leaderboard.getColumns().addAll(colName, colScore);
        leaderboard.setPrefHeight(250);

        // chat
        chatView = new ListView<>();
        chatView.setItems(chatItems);
        chatView.setPrefHeight(250);

        // Dark theme styles for UI controls
        chatView.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6;");
        leaderboard.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6;");


        chatInput = new TextField();
        chatInput.setPromptText("Type message + Enter");
        chatInput.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6; -fx-prompt-text-fill: #777;");
        chatInput.setOnAction(e -> {
            String t = chatInput.getText();
            chatInput.clear();
            if (t != null && !t.isBlank()) sendChat(t.trim());
        });

        // When focusing chat input, disable movement keys (and stop movement)
        chatInput.focusedProperty().addListener((obs, was, isNow) -> {
            if (isNow) {
                gameControlEnabled = false;
                pressed.clear();
                desiredDx = 0;
                desiredDy = 0;
                sendInput(0, 0);
            }
        });

        // Room controls
        roomIdField = new TextField();
        roomIdField.setPromptText("Room id (optional)");
        roomIdField.setStyle("-fx-control-inner-background: #1f1f1f; -fx-background-color: #1f1f1f; -fx-text-fill: #e6e6e6; -fx-prompt-text-fill: #777;");

        Button btnJoinRoom = new Button("Join");
        btnJoinRoom.setMaxWidth(Double.MAX_VALUE);
        btnJoinRoom.setOnAction(e -> sendJoinRoom(resolveRoomId(roomIdField.getText()), false));

        Button btnCreateRoom = new Button("Create");
        btnCreateRoom.setMaxWidth(Double.MAX_VALUE);
        btnCreateRoom.setOnAction(e -> sendCreateRoom(roomIdField.getText()));

        btnSpectatePlay = new Button("Spectate");
        btnSpectatePlay.setMaxWidth(Double.MAX_VALUE);
        btnSpectatePlay.setOnAction(e -> {
            String rid = resolveRoomId(roomIdField.getText());
            if (isSpectator) sendJoinRoom(rid, false); // play
            else sendJoinRoom(rid, true); // spectate
        });

        HBox roomBtns = new HBox(8, btnJoinRoom, btnCreateRoom, btnSpectatePlay);
        roomBtns.setAlignment(Pos.CENTER);
        HBox.setHgrow(btnJoinRoom, Priority.ALWAYS);
        HBox.setHgrow(btnCreateRoom, Priority.ALWAYS);
        HBox.setHgrow(btnSpectatePlay, Priority.ALWAYS);

        VBox right = new VBox(10,
                roomLabel,
                help,
                new Label("Rooms"),
                roomIdField,
                roomBtns,
                new Label("Leaderboard"),
                leaderboard,
                new Label("Chat"),
                chatView,
                chatInput
        );
        right.setPadding(new Insets(10));
        right.setPrefWidth(320);

        BorderPane root = new BorderPane();

        StackPane centerStack = new StackPane(canvas, profileOverlay);
        StackPane.setAlignment(profileOverlay, Pos.TOP_RIGHT);
        root.setCenter(centerStack);
        root.setRight(right);

        // Top bar (dark)
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(10, spacer, profileWidget);
        topBar.setAlignment(Pos.CENTER_RIGHT);
        topBar.setPadding(new Insets(6));
        topBar.setStyle("-fx-background-color: #151515; -fx-border-color: #222; -fx-border-width: 0 0 1 0;");
        root.setTop(topBar);

        // Dark theme base
        root.setStyle("-fx-background-color: #141414; -fx-text-fill: #e6e6e6;");

        gameScene = new Scene(root);
        gameScene.setFill(Color.web("#141414"));

        // Key events on whole scene (only when control is enabled)
        gameScene.setOnKeyPressed(e -> {
            if (!gameControlEnabled) return;

            pressed.add(e.getCode());

            if (e.getCode() == KeyCode.Q) sendChat("Всем привет");
            if (e.getCode() == KeyCode.E) sendChat("Вхавха");
            if (e.getCode() == KeyCode.R) sendChat("Рачки))");

            recomputeDesiredDir();
        });
        gameScene.setOnKeyReleased(e -> {
            if (!gameControlEnabled) return;

            pressed.remove(e.getCode());
            recomputeDesiredDir();
        });

        // IMPORTANT: gameScene.getWindow() is null until the scene is set on a Stage.
        // Attach focus listener when the window becomes available.
        gameScene.windowProperty().addListener((obs, oldW, newW) -> {
            if (newW == null) return;
            newW.focusedProperty().addListener((o, was, isNow) -> {
                if (!isNow) {
                    gameControlEnabled = false;
                    pressed.clear();
                    desiredDx = 0;
                    desiredDy = 0;
                    sendInput(0, 0);
                }
            });
        });

        // render + network pump
        AnimationTimer timer = new AnimationTimer() {
            long last = 0;
            @Override public void handle(long now) {
                if (last == 0) last = now;
                last = now;

                pumpNetwork();
                render();

                if (gameControlEnabled && now - lastInputSentNs > 33_000_000) { // ~30 Hz
                    sendInput(desiredDx, desiredDy);
                    lastInputSentNs = now;
                }
            }
        };
        timer.start();
    }

    private void recomputeDesiredDir() {
        int dx = 0, dy = 0;
        if (pressed.contains(KeyCode.LEFT) || pressed.contains(KeyCode.A)) dx = -1;
        if (pressed.contains(KeyCode.RIGHT) || pressed.contains(KeyCode.D)) dx = 1;
        if (pressed.contains(KeyCode.UP) || pressed.contains(KeyCode.W)) dy = -1;
        if (pressed.contains(KeyCode.DOWN) || pressed.contains(KeyCode.S)) dy = 1;

        if (dx != 0 && dy != 0) dy = 0; // no diagonal

        desiredDx = dx;
        desiredDy = dy;
    }

    private void doAuth(String mode) {
        String u = tfUser.getText() == null ? "" : tfUser.getText().trim();
        String p = (cbShowPass.isSelected() ? tfPassVisible.getText() : pfPass.getText());
        if (p == null) p = "";
        p = p.trim();

        if (u.isBlank() || p.isBlank()) {
            lblLoginStatus.setText("Username and Password must not be empty.");
            return;
        }

        try {
            ensureConnected();
            ObjectNode msg = Net.MAPPER.createObjectNode();
            msg.put("type", mode);
            msg.put("username", u);
            msg.put("password", p);
            sendJson(msg);

            lblLoginStatus.setText("Sent " + mode + "...");
        } catch (Exception ex) {
            lblLoginStatus.setText("Connect error: " + ex.getMessage());
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;

        socket = new Socket("127.0.0.1", 7777);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        running.set(true);
        readerThread = new Thread(this::readLoop, "net-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = in.readLine()) != null) {
                inbox.add(line);
            }
        } catch (IOException ignored) {
        } finally {
            running.set(false);
        }
    }

    private void sendJson(ObjectNode n) {
        try {
            String line = Net.MAPPER.writeValueAsString(n);
            out.println(line);
        } catch (Exception ignored) {}
    }

    /**
     * Compatibility helper: some UI actions call sendNode().
     * It is equivalent to sendJson().
     */
    private void sendNode(ObjectNode n) {
        if (out == null) return;
        sendJson(n);
    }


    private void sendInput(int dx, int dy) {
        if (out == null) return;
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "input");
        n.put("dx", dx);
        n.put("dy", dy);
        sendJson(n);
    }

    private void sendChat(String text) {
        if (out == null) return;
        if (text == null) return;
        if (text.length() > 300) text = text.substring(0, 300);

        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "chat_send");
        n.put("text", text);
        sendJson(n);
    }

    private void sendProfileGet() {
        if (out == null) return;
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "profile_get");
        sendNode(n);
    }

    private void toggleProfile() {
        if (profileOverlay == null) return;
        boolean show = !profileOverlay.isVisible();
        profileOverlay.setVisible(show);
        profileOverlay.setManaged(show);
        if (show) sendProfileGet();
    }




    private String resolveRoomId(String typed) {
        String t = (typed == null) ? "" : typed.trim();
        if (!t.isEmpty()) return t;
        String cur = (currentRoomId == null) ? "" : currentRoomId.trim();
        return cur.isEmpty() ? "MAIN" : cur;
    }


    private void sendCreateRoom(String roomId) {
        if (out == null) return;
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "create_room");
        if (roomId != null) n.put("roomId", roomId.trim());
        sendJson(n);
    }

    private void sendJoinRoom(String roomId, boolean spectator) {
        if (out == null) return;
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "join_room");
        if (roomId != null) n.put("roomId", roomId.trim());
        n.put("spectator", spectator);
        sendJson(n);
    }

    private void pumpNetwork() {
        String line;
        int guard = 0;
        while (guard++ < 200 && (line = inbox.poll()) != null) {
            try {
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");

                switch (type) {
                    case "auth_ok" -> {
                        myUsername = n.path("username").asText("");
                        Platform.runLater(() -> {
                            lblLoginStatus.setText("");
                            stage.setScene(gameScene);
                            if (profileNameLabel != null) profileNameLabel.setText(myUsername);
                            gameControlEnabled = true;
                            if (canvas != null) canvas.requestFocus();
                        });
                    }
                    case "room_joined" -> {
                        String newRoom = n.path("roomId").asText("MAIN");
                        boolean spectator = n.path("spectator").asBoolean(false);

                        boolean roomChanged = !Objects.equals(currentRoomId, newRoom);
                        currentRoomId = newRoom;
                        isSpectator = spectator;

                        if (spectator) {
                            // spectators must not control movement
                            gameControlEnabled = false;
                            pressed.clear();
                            desiredDx = 0;
                            desiredDy = 0;
                            sendInput(0, 0);
                        }

                        Platform.runLater(() -> {
                            roomLabel.setText("Room: " + currentRoomId);
                            if (roomChanged) chatItems.clear();

                            if (btnSpectatePlay != null) {
                                btnSpectatePlay.setText(isSpectator ? "Play" : "Spectate");
                            }

                            // For players, allow regaining control by clicking the field.
                            if (!isSpectator && canvas != null) canvas.requestFocus();
                        });
                    }

                    case "state" -> {
                        Messages.State st = Net.MAPPER.treeToValue(n, Messages.State.class);
                        lastState = st;
                        if (st.roomId != null && !st.roomId.isBlank()) currentRoomId = st.roomId;
                        Platform.runLater(() -> {
                            roomLabel.setText("Room: " + currentRoomId);
                            updateLeaderboard(st);
                        });
                    }

                    case "profile" -> {
                        // Profile stats and achievements (optional feature)
                        if (profileTitle != null) {
                            String name = n.path("username").asText(myUsername);
                            profileTitle.setText("Profile: " + name);
                        }
                        if (profileStats != null) {
                            JsonNode s = n.path("stats");
                            String statsText =
                                    "Kills: " + s.path("kills").asLong(0) + "\n" +
                                            "Total captured area: " + s.path("area").asLong(0) + "\n" +
                                            "Best score: " + s.path("bestScore").asInt(0) + "\n" +
                                            "Best kills in game: " + s.path("bestKillsInGame").asInt(0) + "\n" +
                                            "Best kill streak: " + s.path("bestKillStreak").asInt(0);
                            profileStats.setText(statsText);
                        }
                        if (profileAchievements != null) {
                            ObservableList<String> items = FXCollections.observableArrayList();
                            for (JsonNode a : n.path("achievements")) {
                                String title = a.path("title").asText(a.path("code").asText(""));
                                String desc = a.path("desc").asText("");
                                if (!desc.isBlank()) items.add(title + " — " + desc);
                                else items.add(title);
                            }
                            profileAchievements.setItems(items);
                        }
                    }
                    case "chat_msg" -> {
                        String rid = n.path("roomId").asText("");
                        if (rid != null && !rid.isBlank() && !rid.equals(currentRoomId)) break;

                        String from = n.path("from").asText("?");
                        String text = n.path("text").asText("");
                        Platform.runLater(() -> {
                            chatItems.add(from + ": " + text);
                            if (chatItems.size() > 200) chatItems.remove(0);
                            chatView.scrollTo(chatItems.size() - 1);
                        });
                    }
                    case "error" -> {
                        String reason = n.path("reason").asText("error");
                        Platform.runLater(() -> {
                            if (stage.getScene() == loginScene) lblLoginStatus.setText(reason);
                            else chatItems.add("[error] " + reason);
                        });
                    }
                    default -> { /* ignore */ }
                }
            } catch (Exception ignored) {}
        }
    }

    private void updateLeaderboard(Messages.State st) {
        if (st == null || st.leaderboard == null) return;
        List<LeaderRow> rows = new ArrayList<>();
        for (Messages.LeaderEntry e : st.leaderboard) rows.add(new LeaderRow(e.username, e.bestScore));
        leaderboard.setItems(FXCollections.observableArrayList(rows));
    }

    private void render() {
        Messages.State st = lastState;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#2b2b2b"));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (st == null || st.owners == null) return;

        int cell = st.cellSize;
        int gw = st.gridW;
        int gh = st.gridH;

        double targetW = gw * (double) cell;
        double targetH = gh * (double) cell;
        if (canvas.getWidth() != targetW || canvas.getHeight() != targetH) {
            canvas.setWidth(targetW);
            canvas.setHeight(targetH);
        }

        // Build idx -> color mapping from players in this room (territory color must match player color)
        HashMap<Integer, Color> idxToColor = new HashMap<>();

        // grid lines (cell borders)
        g.setStroke(Color.color(0, 0, 0, 0.55));
        g.setLineWidth(1);
        for (int x = 0; x <= gw; x++) {
            double xx = x * (double) cell + 0.5;
            g.strokeLine(xx, 0, xx, gh * (double) cell);
        }
        for (int y = 0; y <= gh; y++) {
            double yy = y * (double) cell + 0.5;
            g.strokeLine(0, yy, gw * (double) cell, yy);
        }

        if (st.players != null) {
            for (Messages.Player p : st.players) {
                if (p.color == null || p.color.isBlank()) continue;
                try {
                    idxToColor.put(p.idx, Color.web(p.color));
                } catch (Exception ignored) {}
            }
        }

        // territory
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int idx = st.owners[y * gw + x];
                if (idx == 0) continue;

                Color base = idxToColor.get(idx);
                Color c = (base != null)
                        ? base.deriveColor(0, 1, 1, 0.65)
                        : Color.hsb((idx * 70) % 360, 0.65, 0.75, 0.65);

                g.setFill(c);
                g.fillRect(x * cell, y * cell, cell, cell);
            }
        }

        if (st.players != null) {
            for (Messages.Player p : st.players) {
                if (p.trail != null) {
                    Color tc = Color.web(p.color == null ? "#FFFFFF" : p.color, 0.35);
                    g.setFill(tc);
                    double pad = cell * 0.22; // make trail slightly narrower than a full cell
                    double w = cell - pad * 2.0;
                    for (Messages.Cell c : p.trail) {
                        g.fillRect(c.x * cell + pad, c.y * cell + pad, w, w);
                    }
                }

                // small shadow
                g.setFill(Color.color(0, 0, 0, 0.30));
                g.fillOval(p.x + 2, p.y + 2, 16, 16);

                Color pc = Color.web(p.color == null ? "#FFFFFF" : p.color);
                g.setFill(pc);
                g.fillOval(p.x, p.y, 16, 16);

                g.setFill(Color.WHITE);
                g.fillText(p.username, p.x + 18, p.y + 12);
            }
        }
    }

    @Override
    public void stop() throws Exception {
        running.set(false);
        if (socket != null) socket.close();
        super.stop();
    }
}
