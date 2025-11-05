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
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
 * Client (no achievements/stats/emoji).
 * Includes: login/register, rooms (join/create by id), spectator mode, chat, room leaderboard.
 * Latency guard: keeps only the newest state frame and drops older ones.
 */
public final class PaperFxApp extends Application {

    // ---- network ----
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> latestStateLine = new AtomicReference<>(null);
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
    private StackPane canvasHost;
    private Label roomLabel;
    private TextField tfRoomId;
    private CheckBox cbSpectator;
    private Button btnJoin;
    private Button btnCreate;

    private TableView<LeaderRow> leaderboard;

    private ListView<String> chatView;
    private ObservableList<String> chatItems = FXCollections.observableArrayList();
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
                new Label("Server: 127.0.0.1:7777"),
                tfUser,
                passRow,
                new HBox(10, btnLogin, btnRegister),
                lblLoginStatus
        );
        ((HBox)box.getChildren().get(3)).setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20));
        box.setMaxWidth(420);

        BorderPane root = new BorderPane();
        root.setCenter(box);

        loginScene = new Scene(root);
    }

    private void buildGameScene() {
        canvas = new Canvas(800, 600);
        canvasHost = new StackPane(canvas);
        canvasHost.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        canvasHost.setOnMouseClicked(e -> canvasHost.requestFocus()); // regain focus for movement

        roomLabel = new Label("Room: MAIN");

        tfRoomId = new TextField();
        tfRoomId.setPromptText("Room ID (e.g. MAIN, R123...)");

        cbSpectator = new CheckBox("Spectator");

        btnJoin = new Button("Join");
        btnCreate = new Button("Create");

        btnJoin.setOnAction(e -> joinRoom());
        btnCreate.setOnAction(e -> createRoom());

        HBox roomRow = new HBox(8, new Label("Room:"), tfRoomId, cbSpectator, btnJoin, btnCreate);
        roomRow.setAlignment(Pos.CENTER_LEFT);

        Label help = new Label("Move: WASD/Arrows | Quick chat: Q/E/R | Type in chat field + Enter");

        // leaderboard
        leaderboard = new TableView<>();
        leaderboard.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        TableColumn<LeaderRow, String> colName = new TableColumn<>("User");
        colName.setCellValueFactory(c -> c.getValue().usernameProperty());
        TableColumn<LeaderRow, Number> colScore = new TableColumn<>("Score");
        colScore.setCellValueFactory(c -> c.getValue().scoreProperty());
        leaderboard.getColumns().addAll(colName, colScore);
        leaderboard.setPrefHeight(260);

        // chat
        chatView = new ListView<>(chatItems);
        chatView.setPrefHeight(250);

        chatInput = new TextField();
        chatInput.setPromptText("Chat (<=300 chars) — Enter to send");
        chatInput.setOnAction(e -> {
            String t = chatInput.getText();
            chatInput.clear();
            if (t != null && !t.isBlank()) sendChat(t.trim());
            canvasHost.requestFocus(); // return control
        });

        VBox right = new VBox(10,
                roomLabel,
                help,
                roomRow,
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
        root.setCenter(canvasHost);
        root.setRight(right);

        gameScene = new Scene(root);

        // Input: use event filters so UI controls won't steal movement.
        gameScene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressedFiltered);
        gameScene.addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleasedFiltered);

        // focus fix (window is null until scene attached)
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

        new AnimationTimer() {
            @Override public void handle(long now) {
                pumpNetwork();
                render();

                if (now - lastInputSentNs > 33_000_000) { // ~30Hz
                    sendInput(desiredDx, desiredDy);
                    lastInputSentNs = now;
                }
            }
        }.start();

        // Make sure we start with focus on the game
        Platform.runLater(canvasHost::requestFocus);
    }

    private void onKeyPressedFiltered(KeyEvent e) {
        KeyCode code = e.getCode();

        // If user is typing in a text field, do NOT hijack keys (except ESC)
        boolean typing = (gameScene.getFocusOwner() instanceof TextInputControl);
        if (typing && code != KeyCode.ESCAPE) return;

        // ESC: exit typing, return focus to game
        if (code == KeyCode.ESCAPE) {
            canvasHost.requestFocus();
            e.consume();
            return;
        }

        // Quick chat hotkeys (allowed even if not typing, but not while typing)
        if (code == KeyCode.Q) { sendChat("Всем привет"); e.consume(); return; }
        if (code == KeyCode.E) { sendChat("Вхавха"); e.consume(); return; }
        if (code == KeyCode.R) { sendChat("Рачки))"); e.consume(); return; }

        // Movement
        if (isMoveKey(code)) {
            pressed.add(code);
            recomputeDesiredDir();
            e.consume();
        }
    }

    private void onKeyReleasedFiltered(KeyEvent e) {
        KeyCode code = e.getCode();
        boolean typing = (gameScene.getFocusOwner() instanceof TextInputControl);
        if (typing) return;

        if (isMoveKey(code)) {
            pressed.remove(code);
            recomputeDesiredDir();
            e.consume();
        }
    }

    private static boolean isMoveKey(KeyCode c) {
        return c == KeyCode.LEFT || c == KeyCode.RIGHT || c == KeyCode.UP || c == KeyCode.DOWN ||
               c == KeyCode.A || c == KeyCode.D || c == KeyCode.W || c == KeyCode.S;
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
     * Read loop drops older state frames: keeps only the newest "state" line.
     * This prevents "delay grows with time" when network is slower than tick rate.
     */
    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = in.readLine()) != null) {
                if (line.contains("\"type\":\"state\"")) {
                    latestStateLine.set(line);
                } else {
                    inbox.add(line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            running.set(false);
        }
    }

    private void sendJson(ObjectNode n) {
        try {
            out.println(Net.MAPPER.writeValueAsString(n));
        } catch (Exception ignored) {}
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
        canvasHost.requestFocus();
    }

    private void createRoom() {
        if (out == null) return;
        String rid = tfRoomId.getText() == null ? "" : tfRoomId.getText().trim();

        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "create_room");
        n.put("roomId", rid);
        sendJson(n);
        canvasHost.requestFocus();
    }

    // ---- gameplay messages ----

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

    // ---- pump ----

    private void pumpNetwork() {
        // process newest state first (cheap)
        String stLine = latestStateLine.getAndSet(null);
        if (stLine != null) handleLine(stLine);

        // then process other messages (bounded)
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
                    canvasHost.requestFocus();
                });
                case "room_joined" -> {
                    currentRoomId = n.path("roomId").asText("MAIN");
                    Platform.runLater(() -> roomLabel.setText("Room: " + currentRoomId));
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

        // keep canvas in sync with server grid (prevents drift)
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
