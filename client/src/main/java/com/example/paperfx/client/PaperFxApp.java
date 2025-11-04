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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PaperFX client.
 *
 * Fixes:
 * - Low-latency network: keep only latest state frame (drop older state frames)
 * - Reliable keyboard control: uses Scene event filters so controls don't steal WASD/arrows
 */
public final class PaperFxApp extends Application {

    // ---- network ----
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Messages.State> latestStateObj = new AtomicReference<>(null);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ---- ui ----
    private Stage stage;
    private Scene loginScene;
    private Scene gameScene;

    // login ui
    private TextField tfUser;
    private PasswordField pfPass;
    private TextField tfPassVisible;
    private CheckBox cbShowPass;
    private Label lblLoginStatus;

    // game ui
    private Canvas canvas;
    private Label roomLabel;
    private TextField tfRoomId;
    private CheckBox cbSpectator;
    private Button btnJoin;
    private Button btnCreate;

    private TableView<LeaderRow> leaderboard;
    private ComboBox<String> emojiBox;
    private Label statsLabel;

    private ListView<String> chatView;
    private final ObservableList<String> chatItems = FXCollections.observableArrayList();
    private TextField chatInput;

    // ---- state ----
    private volatile Messages.State lastState;
    private volatile String currentRoomId = "MAIN";

    // input
    private final EnumSet<KeyCode> pressed = EnumSet.noneOf(KeyCode.class);
    private int desiredDx = 0;
    private int desiredDy = 0;
    private long lastInputSentNs = 0;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("PaperFX");

        buildLoginScene();
        buildGameScene();

        stage.setScene(loginScene);
        stage.setWidth(1200);
        stage.setHeight(820);
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
        pfPass.textProperty().addListener((o, a, b) -> { if (cbShowPass.isSelected()) tfPassVisible.setText(b); });
        tfPassVisible.textProperty().addListener((o, a, b) -> { if (cbShowPass.isSelected()) pfPass.setText(b); });

        Button btnLogin = new Button("Login");
        Button btnRegister = new Button("Register");
        btnLogin.setFocusTraversable(false);
        btnRegister.setFocusTraversable(false);

        lblLoginStatus = new Label();

        btnLogin.setOnAction(e -> doAuth("login"));
        btnRegister.setOnAction(e -> doAuth("register"));

        HBox passRow = new HBox(8, pfPass, tfPassVisible, cbShowPass);
        passRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(12,
                new Label("Server: 127.0.0.1:7777"),
                tfUser,
                passRow,
                new HBox(10, btnLogin, btnRegister),
                lblLoginStatus
        );
        ((HBox) box.getChildren().get(3)).setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20));
        box.setMaxWidth(420);

        BorderPane root = new BorderPane();
        root.setCenter(box);

        loginScene = new Scene(root);
    }

    private void buildGameScene() {
        canvas = new Canvas(800, 600);
        canvas.setFocusTraversable(true);
        canvas.setOnMouseClicked(e -> canvas.requestFocus());

        roomLabel = new Label("Room: MAIN");

        tfRoomId = new TextField();
        tfRoomId.setPromptText("Room ID (e.g. MAIN, R123...)");

        cbSpectator = new CheckBox("Spectator");
        cbSpectator.setFocusTraversable(false);

        btnJoin = new Button("Join");
        btnCreate = new Button("Create");
        btnJoin.setFocusTraversable(false);
        btnCreate.setFocusTraversable(false);

        btnJoin.setOnAction(e -> joinRoom());
        btnCreate.setOnAction(e -> createRoom());

        HBox roomRow = new HBox(8, new Label("Room:"), tfRoomId, cbSpectator, btnJoin, btnCreate);
        roomRow.setAlignment(Pos.CENTER_LEFT);

        emojiBox = new ComboBox<>();
        emojiBox.setPromptText("Select emoji");
        emojiBox.setMaxWidth(Double.MAX_VALUE);
        emojiBox.setFocusTraversable(false);
        emojiBox.setOnAction(e -> {
            String em = emojiBox.getSelectionModel().getSelectedItem();
            if (em == null) em = "";
            sendSetEmoji(em);
        });

        statsLabel = new Label("");
        statsLabel.setWrapText(true);

        leaderboard = new TableView<>();
        leaderboard.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        leaderboard.setFocusTraversable(false);
        TableColumn<LeaderRow, String> colName = new TableColumn<>("User");
        colName.setCellValueFactory(c -> c.getValue().usernameProperty());
        TableColumn<LeaderRow, Number> colScore = new TableColumn<>("Score");
        colScore.setCellValueFactory(c -> c.getValue().scoreProperty());
        leaderboard.getColumns().addAll(colName, colScore);
        leaderboard.setPrefHeight(260);

        chatView = new ListView<>(chatItems);
        chatView.setPrefHeight(250);
        chatView.setFocusTraversable(false);

        chatInput = new TextField();
        chatInput.setPromptText("Chat (<=300 chars) — Enter to send");
        chatInput.setOnAction(e -> {
            String t = chatInput.getText();
            chatInput.clear();
            if (t != null && !t.isBlank()) sendChat(t.trim());
            // return control to game fast
            canvas.requestFocus();
        });

        VBox right = new VBox(10,
                roomLabel,
                roomRow,
                new Separator(),
                new Label("Emoji"),
                emojiBox,
                statsLabel,
                new Separator(),
                new Label("Leaderboard (room)"),
                leaderboard,
                new Separator(),
                new Label("Chat"),
                chatView,
                chatInput
        );
        right.setPadding(new Insets(10));
        right.setPrefWidth(360);

        BorderPane root = new BorderPane();
        root.setCenter(new StackPane(canvas));
        root.setRight(right);

        gameScene = new Scene(root);

        // If Stage loses focus, stop movement (prevents "stuck keys")
        gameScene.windowProperty().addListener((obs, oldW, newW) -> {
            if (newW == null) return;
            newW.focusedProperty().addListener((o, was, isNow) -> {
                if (!isNow) {
                    pressed.clear();
                    desiredDx = 0;
                    desiredDy = 0;
                    sendInput(0, 0);
                }
            });
        });

        // --- KEYBOARD CONTROL FIX ---
        // Use event filters (capturing phase) so TextField/Table/etc don't swallow arrows/WASD.
        gameScene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressedFilter);
        gameScene.addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleasedFilter);

        // When scene becomes active, return focus to canvas.
        gameScene.windowProperty().addListener((obs, oldW, newW) -> Platform.runLater(canvas::requestFocus));

        new AnimationTimer() {
            @Override public void handle(long now) {
                pumpNetwork();
                render();

                // send input ~30Hz (enough for smoothness)
                if (now - lastInputSentNs > 33_000_000) {
                    sendInput(desiredDx, desiredDy);
                    lastInputSentNs = now;
                }
            }
        }.start();
    }

    private boolean isTypingInTextField() {
        Node focus = gameScene == null ? null : gameScene.getFocusOwner();
        return (focus instanceof TextInputControl);
    }

    private void onKeyPressedFilter(KeyEvent e) {
        // ESC: return focus to game
        if (e.getCode() == KeyCode.ESCAPE) {
            canvas.requestFocus();
            e.consume();
            return;
        }

        // If user is typing in a TextField (room id or chat), do not hijack letter keys.
        // But still allow arrows/WASD control even if focus isn't chatInput specifically.
        boolean typing = isTypingInTextField();

        // Chat quick-macros only when NOT typing in a text field
        if (!typing) {
            if (e.getCode() == KeyCode.Q) { sendChat("Всем привет"); e.consume(); return; }
            if (e.getCode() == KeyCode.E) { sendChat("Вхавха"); e.consume(); return; }
            if (e.getCode() == KeyCode.R) { sendChat("Рачки))"); e.consume(); return; }
        }

        // Movement keys should work always (even if focus on other controls),
        // but do not break typing letters: only WASD/arrows.
        if (isMoveKey(e.getCode())) {
            pressed.add(e.getCode());
            recomputeDesiredDir();
            e.consume(); // stop controls from using arrows (caret navigation)
        }
    }

    private void onKeyReleasedFilter(KeyEvent e) {
        if (isMoveKey(e.getCode())) {
            pressed.remove(e.getCode());
            recomputeDesiredDir();
            e.consume();
        }
    }

    private boolean isMoveKey(KeyCode c) {
        return c == KeyCode.LEFT || c == KeyCode.RIGHT || c == KeyCode.UP || c == KeyCode.DOWN
                || c == KeyCode.W || c == KeyCode.A || c == KeyCode.S || c == KeyCode.D;
    }

    private void recomputeDesiredDir() {
        int dx = 0, dy = 0;
        if (pressed.contains(KeyCode.LEFT) || pressed.contains(KeyCode.A)) dx = -1;
        if (pressed.contains(KeyCode.RIGHT) || pressed.contains(KeyCode.D)) dx = 1;
        if (pressed.contains(KeyCode.UP) || pressed.contains(KeyCode.W)) dy = -1;
        if (pressed.contains(KeyCode.DOWN) || pressed.contains(KeyCode.S)) dy = 1;

        if (dx != 0 && dy != 0) dy = 0;
        desiredDx = dx;
        desiredDy = dy;
    }

    // ---- login/register ----

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

    /**
     * Read loop drops older "state" frames: keeps only the newest state line.
     */
    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = in.readLine()) != null) {
                if (isStateLine(line)) {
                    try {
                        JsonNode node = Net.parse(line);
                        Messages.State st = Net.MAPPER.treeToValue(node, Messages.State.class);
                        latestStateObj.set(st);
                    } catch (Exception ex) {
                        // If something unexpected happened, don't break the reader.
                    }
                } else {
                    inbox.add(line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            running.set(false);
        }
    }

    private boolean isStateLine(String line) {
        if (line == null) return false;
        // tolerate spacing / different field ordering
        return line.contains("\"type\":\"state\"") || line.contains("\"type\": \"state\"");
    }

    private void sendJson(ObjectNode n) {
        try { out.println(Net.MAPPER.writeValueAsString(n)); }
        catch (Exception ignored) {}
    }

    // ---- room controls ----

    private void joinRoom() {
        if (out == null) return;
        String rid = tfRoomId.getText() == null ? "" : tfRoomId.getText().trim();
        if (rid.isBlank()) rid = "MAIN";

        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "join_room");
        n.put("roomId", rid);
        n.put("spectator", cbSpectator.isSelected());
        sendJson(n);

        canvas.requestFocus();
    }

    private void createRoom() {
        if (out == null) return;
        String rid = tfRoomId.getText() == null ? "" : tfRoomId.getText().trim();

        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "create_room");
        n.put("roomId", rid);
        sendJson(n);

        canvas.requestFocus();
    }

    private void requestProfile() {
        if (out == null) return;
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "get_profile");
        sendJson(n);
    }

    // ---- gameplay ----

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

    private void sendSetEmoji(String emoji) {
        if (out == null) return;
        if (emoji == null) emoji = "";
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "set_emoji");
        n.put("emoji", emoji);
        sendJson(n);
    }

    // ---- pump ----

    private void pumpNetwork() {
        // newest decoded state first (parsed in reader thread)
        Messages.State st = latestStateObj.getAndSet(null);
        if (st != null) {
            lastState = st;
            if (st.roomId != null && !st.roomId.isBlank()) currentRoomId = st.roomId;
            Platform.runLater(() -> {
                roomLabel.setText("Room: " + currentRoomId);
                updateLeaderboard(st);
            });
        }

        // other messages (bounded)
        String line;
        int guard = 0;
        while (guard++ < 200 && (line = inbox.poll()) != null) {
            handleLine(line);
        }
    }

    private void handleLine(String line) {
        try {
            JsonNode n = Net.parse(line);
            String type = n.path("type").asText("");

            switch (type) {
                case "auth_ok" -> Platform.runLater(() -> {
                    lblLoginStatus.setText("");
                    stage.setScene(gameScene);
                    canvas.requestFocus();
                });
                case "room_joined" -> {
                    currentRoomId = n.path("roomId").asText("MAIN");
                    Platform.runLater(() -> roomLabel.setText("Room: " + currentRoomId));
                }
                case "profile" -> {
                    String selected = n.path("selectedEmoji").asText("");
                    ArrayList<String> opts = new ArrayList<>();
                    opts.add("");
                    for (JsonNode e : n.path("unlockedEmojis")) opts.add(e.asText(""));

                    long totalKills = n.path("totalKills").asLong(0);
                    long totalScore = n.path("totalScore").asLong(0);
                    int maxMatchScore = n.path("maxMatchScore").asInt(0);
                    int maxMatchKills = n.path("maxMatchKills").asInt(0);
                    int maxKillStreak = n.path("maxKillStreak").asInt(0);

                    Platform.runLater(() -> {
                        emojiBox.setItems(FXCollections.observableArrayList(opts));
                        emojiBox.getSelectionModel().select(selected);
                        statsLabel.setText(
                                "Stats:\n" +
                                        "Total kills: " + totalKills + "\n" +
                                        "Total score: " + totalScore + "\n" +
                                        "Max score/match: " + maxMatchScore + "\n" +
                                        "Max kills/match: " + maxMatchKills + "\n" +
                                        "Max killstreak: " + maxKillStreak
                        );
                    });
                }
                case "achievement_unlocked" -> {
                    String title = n.path("title").asText("Achievement");
                    String emoji = n.path("emoji").asText("");
                    Platform.runLater(() -> {
                        chatItems.add("[ACHIEVEMENT] " + (emoji.isBlank() ? "" : (emoji + " ")) + title);
                        trimChat();
                    });
                }
                                case "chat_msg" -> {
                    String from = n.path("from").asText("?");
                    String text = n.path("text").asText("");
                    Platform.runLater(() -> {
                        chatItems.add(from + ": " + text);
                        trimChat();
                    });
                }
                case "error" -> {
                    String reason = n.path("reason").asText("error");
                    Platform.runLater(() -> {
                        if (stage.getScene() == loginScene) lblLoginStatus.setText(reason);
                        else {
                            chatItems.add("[error] " + reason);
                            trimChat();
                        }
                    });
                }
                default -> { /* ignore */ }
            }
        } catch (Exception ignored) {}
    }

    private void trimChat() {
        if (chatItems.size() > 300) chatItems.remove(0);
        chatView.scrollTo(chatItems.size() - 1);
    }

    private void updateLeaderboard(Messages.State st) {
        if (st == null || st.leaderboard == null) return;
        List<LeaderRow> rows = new ArrayList<>();
        for (Messages.LeaderEntry e : st.leaderboard) {
            rows.add(new LeaderRow(e.username, e.bestScore));
        }
        leaderboard.setItems(FXCollections.observableArrayList(rows));
    }

    // ---- render ----

    private void render() {
        Messages.State st = lastState;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#111"));
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

        // territory
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int idx = st.owners[y * gw + x];
                if (idx == 0) continue;
                Color c = Color.hsb((idx * 70) % 360, 0.65, 0.75, 0.65);
                g.setFill(c);
                g.fillRect(x * cell, y * cell, cell, cell);
            }
        }

        // players + trails
        if (st.players != null) {
            for (Messages.Player p : st.players) {
                if (p.trail != null) {
                    Color tc = Color.web(p.color == null ? "#FFFFFF" : p.color, 0.35);
                    g.setFill(tc);
                    for (Messages.Cell c : p.trail) {
                        g.fillRect(c.x * cell, c.y * cell, cell, cell);
                    }
                }

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
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        super.stop();
    }
}
