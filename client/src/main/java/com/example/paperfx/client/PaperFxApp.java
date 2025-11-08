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

        loginScene = new Scene(root);
    }

    private void buildGameScene() {
        canvas = new Canvas(800, 600);
        canvas.setFocusTraversable(true); // allow keyboard focus
        canvas.setOnMousePressed(e -> {
            gameControlEnabled = true;
            canvas.requestFocus(); // click on game field returns control
        });

        roomLabel = new Label("Room: MAIN");
        Label help = new Label("Move: WASD / Arrows | Chat: Q/E/R quick msgs, or type + Enter");

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

        chatInput = new TextField();
        chatInput.setPromptText("Type message + Enter");
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
        TextField roomIdField = new TextField();
        roomIdField.setPromptText("Room id (optional)");

        Button btnJoinRoom = new Button("Join");
        btnJoinRoom.setMaxWidth(Double.MAX_VALUE);
        btnJoinRoom.setOnAction(e -> sendJoinRoom(roomIdField.getText(), false));

        Button btnCreateRoom = new Button("Create");
        btnCreateRoom.setMaxWidth(Double.MAX_VALUE);
        btnCreateRoom.setOnAction(e -> sendCreateRoom(roomIdField.getText()));

        Button btnSpectate = new Button("Spectate");
        btnSpectate.setMaxWidth(Double.MAX_VALUE);
        btnSpectate.setOnAction(e -> sendJoinRoom(roomIdField.getText(), true));

        HBox roomBtns = new HBox(8, btnJoinRoom, btnCreateRoom, btnSpectate);
        roomBtns.setAlignment(Pos.CENTER);
        HBox.setHgrow(btnJoinRoom, Priority.ALWAYS);
        HBox.setHgrow(btnCreateRoom, Priority.ALWAYS);
        HBox.setHgrow(btnSpectate, Priority.ALWAYS);

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
        root.setCenter(new StackPane(canvas));
        root.setRight(right);

        gameScene = new Scene(root);

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
                            gameControlEnabled = true;
                            if (canvas != null) canvas.requestFocus();
                        });
                    }
                    case "room_joined" -> {
                        String newRoom = n.path("roomId").asText("MAIN");
                        boolean changed = !Objects.equals(currentRoomId, newRoom);
                        currentRoomId = newRoom;
                        Platform.runLater(() -> {
                            roomLabel.setText("Room: " + currentRoomId);
                            if (changed) chatItems.clear();
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
                    case "chat_msg" -> {
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
        if (socket != null) socket.close();
        super.stop();
    }
}
