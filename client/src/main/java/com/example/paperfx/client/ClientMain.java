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
        stage.setTitle("PaperFX v0.5 — solid territory fill");
        stage.setScene(buildLobbyScene(stage));
        stage.setWidth(1100);
        stage.setHeight(780);
        stage.show();
    }

    private Scene buildLobbyScene(Stage stage) {
        TextField host = new TextField("127.0.0.1");
        TextField port = new TextField("7777");
        TextField name = new TextField("player");

        Label info = new Label("Server status: not connected");

        Button connect = new Button("Connect");
        connect.setDefaultButton(true);
        connect.setOnAction(e -> {
            connect.setDisable(true);
            info.setText("Server status: connecting...");
            try {
                client = new NetworkClient(host.getText().trim(), Integer.parseInt(port.getText().trim()), name.getText().trim());
                stage.setScene(buildGameScene(stage));
            } catch (Exception ex) {
                info.setText("Failed: " + ex.getMessage());
                connect.setDisable(false);
                if (client != null) {
                    try { client.close(); } catch (IOException ignored) {}
                    client = null;
                }
            }
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Host:"), host);
        form.addRow(1, new Label("Port:"), port);
        form.addRow(2, new Label("Name:"), name);

        VBox root = new VBox(12,
                new Label("PaperFX — JavaFX client + authoritative server"),
                form,
                connect,
                info
        );
        root.setPadding(new Insets(20));
        return new Scene(root);
    }

    private Scene buildGameScene(Stage stage) {
        Canvas canvas = new Canvas(800, 600);

        Label status = new Label();
        status.setMinWidth(420);

        VBox right = new VBox(10,
                new Label("Controls: arrows / WASD"),
                status,
                new Separator(),
                new Label("После замыкания следа:"),
                new Label("- заливка всей территории (owners[])"),
                new Label("- след становится частью территории"),
                new Label("- score += только новые клетки")
        );
        right.setPadding(new Insets(10));
        right.setPrefWidth(420);

        BorderPane root = new BorderPane();
        root.setCenter(new StackPane(canvas));
        root.setRight(right);

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> { keysDown.add(e.getCode()); updateInput(); });
        scene.setOnKeyReleased(e -> { keysDown.remove(e.getCode()); updateInput(); });

        GraphicsContext g = canvas.getGraphicsContext2D();

        new AnimationTimer() {
            @Override public void handle(long now) {
                if (client == null) return;
                Messages.State st = client.latestState.get();

                status.setText(
                        "Server: " + client.status.get() +
                        "\nMy idx: " + (client.myIdx.get() == null ? "-" : client.myIdx.get()) +
                        "\nTick: " + (st == null ? "-" : st.tick)
                );

                render(g, st, client.myId.get());
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

    private void updateInput() {
        int dx = 0, dy = 0;
        if (keysDown.contains(KeyCode.LEFT) || keysDown.contains(KeyCode.A)) dx -= 1;
        if (keysDown.contains(KeyCode.RIGHT) || keysDown.contains(KeyCode.D)) dx += 1;
        if (keysDown.contains(KeyCode.UP) || keysDown.contains(KeyCode.W)) dy -= 1;
        if (keysDown.contains(KeyCode.DOWN) || keysDown.contains(KeyCode.S)) dy += 1;

        if (dx != lastDx || dy != lastDy) {
            lastDx = dx; lastDy = dy;
            if (client != null) client.sendInput(dx, dy);
        }
    }

    private void render(GraphicsContext g, Messages.State st, String myId) {
        g.setFill(Color.rgb(18, 18, 22));
        g.fillRect(0, 0, 800, 600);

        if (st == null) {
            g.setFill(Color.LIGHTGRAY);
            g.fillText("Waiting for state...", 20, 30);
            return;
        }

        Map<Integer, Color> idxToColor = new HashMap<>();
        if (st.players != null) {
            for (Messages.Player p : st.players) {
                idxToColor.put(p.idx, parseHex(p.color, Color.CORNFLOWERBLUE));
            }
        }

        // Territory: SOLID fill based on owners[]
        if (st.owners != null) {
            int w = st.gridW, h = st.gridH, cell = st.cellSize;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int owner = st.owners[row + x];
                    if (owner == 0) continue;
                    Color base = idxToColor.getOrDefault(owner, Color.GRAY);
                    g.setFill(base);
                    g.fillRect(x * cell, y * cell, cell, cell);
                }
            }
        }

        // Trails overlay
        if (st.players != null) {
            for (Messages.Player p : st.players) {
                if (p.trail == null) continue;
                Color base = parseHex(p.color, Color.CORNFLOWERBLUE);
                g.setFill(new Color(base.getRed(), base.getGreen(), base.getBlue(), 0.55));
                for (Messages.Cell c : p.trail) {
                    g.fillRect(c.x * st.cellSize, c.y * st.cellSize, st.cellSize, st.cellSize);
                }
            }
        }

        // Players
        if (st.players != null) {
            for (Messages.Player p : st.players) {
                Color base = parseHex(p.color, Color.CORNFLOWERBLUE);
                double size = 16;

                g.setFill(Color.BLACK);
                g.fillRoundRect(p.x, p.y, size, size, 6, 6);

                g.setFill(base);
                g.fillRoundRect(p.x + 2, p.y + 2, size - 4, size - 4, 5, 5);

                if (p.id != null && p.id.equals(myId)) {
                    g.setStroke(Color.WHITE);
                    g.strokeRoundRect(p.x - 2, p.y - 2, size + 4, size + 4, 8, 8);
                }

                g.setFill(Color.WHITE);
                g.fillText(p.name + " (" + p.score + ")", p.x, p.y - 6);
            }
        }
    }

    private static Color parseHex(String hex, Color fallback) {
        if (hex == null) return fallback;
        try { return Color.web(hex); } catch (Exception e) { return fallback; }
    }

    public static void main(String[] args) { launch(args); }
}
