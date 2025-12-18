package com.example.paperfx.client;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Клиент PaperFX (транспорт UDP, Java 17).
 * 1 UDP-датаграмма == 1 JSON-сообщение (в конце добавляется \n для удобства).
 *
 * ВАЖНО: сервер требует авторизацию (login/register) перед игровыми действиями.
 */
public final class PaperFxApp extends Application {

    // ---------------- сеть (UDP) ----------------
    private DatagramSocket udp;
    private InetSocketAddress serverAddr;
    private volatile boolean netRunning = false;
    private Thread netThread;
    private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();

    // адрес по умолчанию (при желании можно сделать настраиваемым)
    private String host = "127.0.0.1";
    private int port = 7777;

    // ---------------- сцены UI ----------------
    private Stage stage;
    private Scene loginScene;
    private Scene gameScene;

    // UI входа
    private TextField tfUser;
    private PasswordField pfPass;
    private TextField tfPassVisible;
    private CheckBox cbShowPass;
    private Label lblLoginStatus;

    // UI игры
    private Canvas canvas;
    private GraphicsContext g;

    private TextArea chatLog;
    private TextField chatInput;

    private TextField roomIdField;
    private Button btnJoinRoom;
    private Button btnCreateRoom;
    private Button btnSpectateOrPlay;

    private Label statusLabel;

    // виджет профиля
    private HBox profileBox;
    private Label profileName;
    private StackPane profileDot;
    private VBox profilePopup;
    private boolean profilePopupVisible = false;

    // игровой ввод
    private int wantDx = 0, wantDy = 0;
    private boolean gameControlEnabled = true;

    // контекст авторизации/игрока
    private String myUsername = "";
    private String playerId = null;
    private String roomId = "MAIN";
    private boolean spectator = false;

    // состояние от сервера
    private int CELL = 10, GRID_W = 80, GRID_H = 60;
    private int[] owners = new int[GRID_W * GRID_H];
    private final Map<Integer, String> idxToColor = new HashMap<>();
    private final Map<String, PlayerView> players = new HashMap<>();

    // визуальные параметры
    private final Color bgCell = Color.web("#2b2b2b");      // тёмно-серый
    private final Color gridLine = Color.rgb(0, 0, 0, 0.55);// почти чёрный (полупрозрачный)
    private final Color uiBg = Color.web("#1e1e1e");
    private final Color uiBorder = Color.web("#333333");
    private final Color uiText = Color.web("#dddddd");

    private static final class PlayerView {
        String id;
        int idx;
        String name;
        double x, y;
        int score;
        Color color;
        List<int[]> trail; // список клеток
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        stage.setTitle("PaperFX");

        loginScene = buildLoginScene();
        gameScene = buildGameScene();

        stage.setScene(loginScene);
        stage.setWidth(1100);
        stage.setHeight(760);
        stage.show();

        // Открываем UDP-сокет заранее, чтобы login/register могли сразу отправлять сообщения
        try {
            ensureConnected(host, port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        new AnimationTimer() {
            @Override public void handle(long now) {
                pumpNetwork(250);
                if (stage.getScene() == gameScene) draw();
            }
        }.start();
    }

    // ---------------- сцена входа ----------------

    private Scene buildLoginScene() {
        BorderPane root = new BorderPane();
        root.setBackground(new Background(new BackgroundFill(uiBg, CornerRadii.EMPTY, Insets.EMPTY)));
        root.setPadding(new Insets(20));

        Label title = new Label("PaperFX — Login (UDP)");
        title.setTextFill(uiText);
        title.setFont(Font.font(18));

        tfUser = new TextField();
        tfUser.setPromptText("Username");
        styleInput(tfUser);

        pfPass = new PasswordField();
        pfPass.setPromptText("Password");
        styleInput(pfPass);

        tfPassVisible = new TextField();
        tfPassVisible.setPromptText("Password");
        styleInput(tfPassVisible);
        tfPassVisible.setManaged(false);
        tfPassVisible.setVisible(false);

        cbShowPass = new CheckBox("Show password");
        cbShowPass.setTextFill(uiText);
        cbShowPass.selectedProperty().addListener((obs, oldV, isOn) -> {
            if (isOn) {
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

        Button btnLogin = new Button("Login");
        Button btnRegister = new Button("Register");
        styleButton(btnLogin);
        styleButton(btnRegister);

        lblLoginStatus = new Label("");
        lblLoginStatus.setTextFill(Color.web("#f9c74f"));

        btnLogin.setOnAction(e -> doAuth("login"));
        btnRegister.setOnAction(e -> doAuth("register"));

        // Enter в поле пароля выполняет вход
        pfPass.setOnAction(e -> doAuth("login"));
        tfPassVisible.setOnAction(e -> doAuth("login"));

        HBox btnRow = new HBox(10, btnLogin, btnRegister);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10,
                title,
                new Label("Username"),
                tfUser,
                new Label("Password"),
                new StackPane(pfPass, tfPassVisible),
                cbShowPass,
                btnRow,
                lblLoginStatus
        );
        for (var n : box.getChildren()) if (n instanceof Label l) l.setTextFill(uiText);
        box.setMaxWidth(420);
        box.setPadding(new Insets(16));
        box.setBackground(new Background(new BackgroundFill(Color.web("#252526"), new CornerRadii(10), Insets.EMPTY)));
        box.setBorder(new Border(new BorderStroke(uiBorder, BorderStrokeStyle.SOLID, new CornerRadii(10), new BorderWidths(1))));

        root.setCenter(box);
        return new Scene(root);
    }

    private void doAuth(String mode) {
        String u = tfUser.getText() == null ? "" : tfUser.getText().trim();
        String p = (cbShowPass.isSelected() ? tfPassVisible.getText() : pfPass.getText());
        p = (p == null) ? "" : p.trim();

        if (u.isBlank() || p.isBlank()) {
            lblLoginStatus.setText("Username and Password must not be empty");
            return;
        }

        try {
            ensureConnected(host, port);

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

    // ---------------- сцена игры ----------------

    private Scene buildGameScene() {
        BorderPane root = new BorderPane();
        root.setBackground(new Background(new BackgroundFill(uiBg, CornerRadii.EMPTY, Insets.EMPTY)));

        // центрируем canvas
        canvas = new Canvas(GRID_W * CELL, GRID_H * CELL);
        canvas.setFocusTraversable(true);
        g = canvas.getGraphicsContext2D();

        StackPane center = new StackPane(canvas);
        center.setPadding(new Insets(8));
        root.setCenter(center);

        // правая панель (комнаты + чат)
        VBox right = new VBox(8);
        right.setPadding(new Insets(8));
        right.setPrefWidth(320);
        right.setBackground(new Background(new BackgroundFill(uiBg, CornerRadii.EMPTY, Insets.EMPTY)));

        statusLabel = new Label("connected");
        statusLabel.setTextFill(uiText);

        // панель комнат
        roomIdField = new TextField();
        roomIdField.setPromptText("room id (e.g. MAIN)");
        styleInput(roomIdField);

        btnJoinRoom = new Button("Join");
        btnCreateRoom = new Button("Create");
        btnSpectateOrPlay = new Button("Spectate");

        styleButton(btnJoinRoom);
        styleButton(btnCreateRoom);
        styleButton(btnSpectateOrPlay);

        HBox roomBtns = new HBox(6, btnJoinRoom, btnCreateRoom, btnSpectateOrPlay);
        roomBtns.setAlignment(Pos.CENTER_LEFT);

        // чат
        chatLog = new TextArea();
        chatLog.setEditable(false);
        chatLog.setWrapText(true);
        chatLog.setPrefRowCount(18);
        styleArea(chatLog);

        chatInput = new TextField();
        chatInput.setPromptText("type message and press Enter");
        styleInput(chatInput);

        right.getChildren().addAll(statusLabel, new Label("Rooms"), roomIdField, roomBtns, new Label("Chat"), chatLog, chatInput);
        for (var n : right.getChildren()) if (n instanceof Label l) { l.setTextFill(uiText); l.setFont(Font.font(12)); }

        root.setRight(right);

        // верхняя панель с профилем
        BorderPane top = new BorderPane();
        top.setPadding(new Insets(8));
        top.setBackground(new Background(new BackgroundFill(uiBg, CornerRadii.EMPTY, Insets.EMPTY)));
        top.setBorder(new Border(new BorderStroke(uiBorder, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));

        Label title = new Label("PaperFX (UDP)");
        title.setTextFill(uiText);
        title.setFont(Font.font(14));

        profileBox = buildProfileWidget();
        top.setLeft(title);
        top.setRight(profileBox);

        root.setTop(top);

        Scene scene = new Scene(root);
        hookInput(scene);
        hookActions();
        return scene;
    }

    private void hookActions() {
        btnJoinRoom.setOnAction(e -> sendJoinRoom(roomIdField.getText(), false));
        btnCreateRoom.setOnAction(e -> sendCreateRoom(roomIdField.getText()));
        btnSpectateOrPlay.setOnAction(e -> {
            String target = (roomIdField.getText() == null || roomIdField.getText().isBlank()) ? roomId : roomIdField.getText();
            sendJoinRoom(target, !spectator);
        });

        chatInput.setOnAction(e -> {
            String text = chatInput.getText();
            chatInput.clear();
            if (text == null || text.trim().isEmpty()) return;
            sendChat(text.trim());
        });

        // фиксация фокуса: клик по canvas возвращает управление
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                canvas.requestFocus();
                gameControlEnabled = true;
            }
        });

        // при фокусе на вводе чата: отключаем управление и отправляем стоп-ввод
        chatInput.focusedProperty().addListener((obs, was, isNow) -> {
            if (isNow) {
                gameControlEnabled = false;
                sendInput(0, 0);
            }
        });
    }

    private void hookInput(Scene scene) {
        scene.setOnKeyPressed(e -> {
            if (!gameControlEnabled || spectator) return;
            KeyCode k = e.getCode();
            if (k == KeyCode.W || k == KeyCode.UP) { wantDx = 0; wantDy = -1; sendInput(wantDx, wantDy); }
            else if (k == KeyCode.S || k == KeyCode.DOWN) { wantDx = 0; wantDy = 1; sendInput(wantDx, wantDy); }
            else if (k == KeyCode.A || k == KeyCode.LEFT) { wantDx = -1; wantDy = 0; sendInput(wantDx, wantDy); }
            else if (k == KeyCode.D || k == KeyCode.RIGHT) { wantDx = 1; wantDy = 0; sendInput(wantDx, wantDy); }
        });

        scene.setOnKeyReleased(e -> {
            if (!gameControlEnabled || spectator) return;
            KeyCode k = e.getCode();
            if (k == KeyCode.W || k == KeyCode.UP || k == KeyCode.S || k == KeyCode.DOWN ||
                    k == KeyCode.A || k == KeyCode.LEFT || k == KeyCode.D || k == KeyCode.RIGHT) {
                wantDx = 0; wantDy = 0;
                sendInput(0, 0);
            }
        });
    }

    // ---------------- сеть UDP ----------------

    private void ensureConnected(String host, int port) throws IOException {
        if (udp != null && !udp.isClosed()) return;
        serverAddr = new InetSocketAddress(InetAddress.getByName(host), port);
        udp = new DatagramSocket(); // локальный порт выбирается ОС (эпемерный)
        udp.connect(serverAddr);

        netRunning = true;
        netThread = new Thread(this::readLoop, "udp-read");
        netThread.setDaemon(true);
        netThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[64 * 1024];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (netRunning && udp != null && !udp.isClosed()) {
            try {
                udp.receive(p);
                String s = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                for (String line : s.split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) inbox.add(t);
                }
            } catch (IOException ex) {
                inbox.add(makeLocalError("net_reader_error", ex.getMessage()));
            }
        }
    }

    private static String makeLocalError(String reason, String details) {
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "error");
        n.put("reason", reason);
        if (details != null) n.put("details", details);
        try { return Net.MAPPER.writeValueAsString(n); }
        catch (Exception e) { return "{\"type\":\"error\",\"reason\":\"" + reason + "\"}"; }
    }

    private void sendJson(ObjectNode node) {
        if (udp == null || udp.isClosed() || serverAddr == null) return;
        try {
            byte[] data = (Net.MAPPER.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length, serverAddr);
            udp.send(pkt);
        } catch (Exception ex) {
            appendChat("[error] send failed: " + ex.getMessage());
        }
    }

    private void sendNode(JsonNode node) {
        if (node instanceof ObjectNode on) sendJson(on);
        else {
            ObjectNode wrap = Net.MAPPER.createObjectNode();
            wrap.put("type", "error");
            wrap.put("reason", "client_sendNode_requires_object");
            sendJson(wrap);
        }
    }

    // ---------------- вспомогательные методы протокола ----------------

    private void sendInput(int dx, int dy) {
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "input");
        n.put("dx", dx);
        n.put("dy", dy);
        sendJson(n);
    }

    private void sendChat(String text) {
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "chat_send");
        n.put("text", text);
        sendJson(n);
    }

    private void sendJoinRoom(String rid, boolean spectate) {
        String target = (rid == null || rid.isBlank()) ? "MAIN" : rid.trim();
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "join_room");
        n.put("roomId", target);
        n.put("spectator", spectate);
        sendJson(n);
    }

    private void sendCreateRoom(String rid) {
        String target = (rid == null) ? "" : rid.trim();
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "create_room");
        n.put("roomId", target);
        sendJson(n);
    }

    private void sendProfileGet() {
        ObjectNode n = Net.MAPPER.createObjectNode();
        n.put("type", "profile_get");
        sendNode(n);
    }

    // ---------------- обработка входящих сообщений ----------------

    private void pumpNetwork(int max) {
        for (int i = 0; i < max; i++) {
            String line = inbox.poll();
            if (line == null) break;

            try {
                JsonNode n = Net.MAPPER.readTree(line);
                String type = n.path("type").asText("");

                switch (type) {
                    case "error" -> {
                        String reason = n.path("reason").asText("error");
                        // показываем на экране входа, если он сейчас активен
                        if (stage.getScene() == loginScene) {
                            Platform.runLater(() -> lblLoginStatus.setText(reason));
                        } else {
                            appendChat("[error] " + reason);
                        }
                    }
                    case "auth_ok" -> {
                        myUsername = n.path("username").asText("");
                        Platform.runLater(() -> {
                            lblLoginStatus.setText("");
                            profileName.setText(myUsername);
                            stage.setScene(gameScene);
                            gameControlEnabled = true;
                            canvas.requestFocus();
                        });
                    }
                    case "chat_msg" -> {
                        String from = n.path("from").asText("?");
                        String text = n.path("text").asText("");
                        appendChat(from + ": " + text);
                    }
                    case "room_joined" -> {
                        roomId = n.path("roomId").asText(roomId);
                        spectator = n.path("spectator").asBoolean(false);
                        playerId = n.path("playerId").asText(null);
                        btnSpectateOrPlay.setText(spectator ? "Play" : "Spectate");
                        appendChat("[system] joined " + roomId + (spectator ? " as spectator" : ""));
                    }
                    case "state" -> applyState(n);
                    case "profile" -> showProfileFromServer(n);
                    default -> {
                        // неизвестные сообщения игнорируем
                    }
                }
            } catch (Exception ex) {
                appendChat("[error] bad packet: " + ex.getMessage());
            }
        }
    }

    private void applyState(JsonNode n) {
        int cell = n.path("cell").asInt(CELL);
        int gw = n.path("w").asInt(GRID_W);
        int gh = n.path("h").asInt(GRID_H);

        if (cell != CELL || gw != GRID_W || gh != GRID_H) {
            CELL = cell; GRID_W = gw; GRID_H = gh;
            owners = new int[GRID_W * GRID_H];
            canvas.setWidth(GRID_W * (double) CELL);
            canvas.setHeight(GRID_H * (double) CELL);
        }

        JsonNode ownersN = n.get("owners");
        if (ownersN != null && ownersN.isArray() && ownersN.size() == owners.length) {
            for (int i = 0; i < owners.length; i++) owners[i] = ownersN.get(i).asInt(0);
        }

        idxToColor.clear();
        players.clear();

        JsonNode ps = n.get("players");
        if (ps != null && ps.isArray()) {
            for (JsonNode p : ps) {
                PlayerView pv = new PlayerView();
                pv.id = p.path("id").asText("");
                pv.idx = p.path("idx").asInt(0);
                pv.name = p.path("username").asText("?");
                pv.x = p.path("x").asDouble(0);
                pv.y = p.path("y").asDouble(0);
                pv.score = p.path("score").asInt(0);
                pv.color = parseColor(p.path("color").asText("#4CC9F0"));

                pv.trail = new ArrayList<>();
                JsonNode trail = p.get("trail");
                if (trail != null && trail.isArray()) {
                    for (JsonNode c : trail) pv.trail.add(new int[]{c.path("x").asInt(), c.path("y").asInt()});
                }

                players.put(pv.id, pv);
                idxToColor.put(pv.idx, p.path("color").asText("#4CC9F0"));
            }
        }
    }

    // ---------------- отрисовка ----------------

    private void draw() {
        // фоновые клетки
        g.setFill(bgCell);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // заливка территорий (цвет по игроку)
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                int idx = owners[y * GRID_W + x];
                if (idx == 0) continue;
                String colStr = idxToColor.get(idx);
                if (colStr == null) continue;
                Color c = parseColor(colStr);
                g.setFill(c.deriveColor(0, 1, 1, 0.85));
                g.fillRect(x * CELL, y * CELL, CELL, CELL);
            }
        }

        // линии сетки
        g.setStroke(gridLine);
        g.setLineWidth(1.0);
        for (int x = 0; x <= GRID_W; x++) {
            double px = x * (double) CELL + 0.5;
            g.strokeLine(px, 0, px, GRID_H * (double) CELL);
        }
        for (int y = 0; y <= GRID_H; y++) {
            double py = y * (double) CELL + 0.5;
            g.strokeLine(0, py, GRID_W * (double) CELL, py);
        }

        // следы (тоньше и полупрозрачные)
        for (PlayerView pv : players.values()) {
            if (pv.trail == null || pv.trail.isEmpty()) continue;
            Color trailC = pv.color.deriveColor(0, 1, 1, 0.35);
            g.setFill(trailC);
            double inset = CELL * 0.22;
            double w = CELL - inset * 2;
            for (int[] cell : pv.trail) {
                g.fillRect(cell[0] * CELL + inset, cell[1] * CELL + inset, w, w);
            }
        }

        // игроки (точка + тень)
        for (PlayerView pv : players.values()) {
            double r = 7;
            double cx = pv.x + 8;
            double cy = pv.y + 8;

            // тень
            g.setFill(Color.rgb(0, 0, 0, 0.35));
            g.fillOval(cx - r + 1.5, cy - r + 2.0, r * 2, r * 2);

            // тело
            g.setFill(pv.color);
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    // ---------------- UI профиля ----------------

    private HBox buildProfileWidget() {
        profileDot = new StackPane();
        profileDot.setPrefSize(22, 22);
        profileDot.setMaxSize(22, 22);
        profileDot.setBackground(new Background(new BackgroundFill(Color.web("#3c3c3c"), new CornerRadii(99), Insets.EMPTY)));
        profileDot.setBorder(new Border(new BorderStroke(uiBorder, BorderStrokeStyle.SOLID, new CornerRadii(99), new BorderWidths(1))));

        profileName = new Label("guest");
        profileName.setTextFill(uiText);

        HBox box = new HBox(8, profileDot, profileName);
        box.setAlignment(Pos.CENTER_RIGHT);

        profilePopup = new VBox(6);
        profilePopup.setPadding(new Insets(10));
        profilePopup.setBackground(new Background(new BackgroundFill(Color.web("#252526"), new CornerRadii(8), Insets.EMPTY)));
        profilePopup.setBorder(new Border(new BorderStroke(uiBorder, BorderStrokeStyle.SOLID, new CornerRadii(8), new BorderWidths(1))));
        profilePopup.getChildren().add(makePopupLabel("Profile will appear here…"));
        profilePopup.setVisible(false);

        StackPane wrapper = new StackPane(box, profilePopup);
        StackPane.setAlignment(profilePopup, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(profilePopup, new Insets(34, 0, 0, 0));

        box.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            profilePopupVisible = !profilePopupVisible;
            profilePopup.setVisible(profilePopupVisible);
            if (profilePopupVisible) sendProfileGet();
        });

        HBox out = new HBox(wrapper);
        out.setAlignment(Pos.CENTER_RIGHT);
        return out;
    }

    private Label makePopupLabel(String s) {
        Label l = new Label(s);
        l.setTextFill(uiText);
        l.setWrapText(true);
        return l;
    }

    private void showProfileFromServer(JsonNode n) {
        profilePopup.getChildren().clear();
        String user = n.path("username").asText(profileName.getText());
        profilePopup.getChildren().add(makePopupLabel("User: " + user));

        JsonNode stats = n.get("stats");
        if (stats != null && stats.isObject()) {
            profilePopup.getChildren().add(makePopupLabel("Kills: " + stats.path("totalKills").asLong(0)));
            profilePopup.getChildren().add(makePopupLabel("Total area: " + stats.path("totalArea").asLong(0)));
            profilePopup.getChildren().add(makePopupLabel("Best score: " + stats.path("bestScore").asLong(0)));
            profilePopup.getChildren().add(makePopupLabel("Best kills/game: " + stats.path("bestKillsInGame").asLong(0)));
            profilePopup.getChildren().add(makePopupLabel("Best kill streak: " + stats.path("bestKillStreak").asLong(0)));
        }

        JsonNode ach = n.get("achievements");
        if (ach != null && ach.isArray()) {
            profilePopup.getChildren().add(makePopupLabel("Achievements:"));
            for (JsonNode a : ach) profilePopup.getChildren().add(makePopupLabel("• " + a.asText()));
        }
    }

    // ---------------- вспомогательные функции ----------------

    private void appendChat(String s) {
        Platform.runLater(() -> {
            if (chatLog != null) {
                if (chatLog.getText().length() > 50_000) chatLog.clear();
                chatLog.appendText(s + "\n");
            }
        });
    }

    private static Color parseColor(String hex) {
        try { return Color.web(hex); }
        catch (Exception e) { return Color.web("#4CC9F0"); }
    }

    private void styleInput(TextField f) {
        f.setStyle("-fx-background-color: #2d2d30; -fx-text-fill: #dddddd; -fx-prompt-text-fill: #777777; -fx-border-color: #3c3c3c;");
    }

    private void styleArea(TextArea a) {
        a.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: #dddddd; -fx-border-color: #3c3c3c;");
    }

    private void styleButton(Button b) {
        b.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: #dddddd; -fx-border-color: #4c4c4c;");
    }

    @Override
    public void stop() {
        netRunning = false;
        try { if (udp != null) udp.close(); } catch (Exception ignored) {}
    }
}
