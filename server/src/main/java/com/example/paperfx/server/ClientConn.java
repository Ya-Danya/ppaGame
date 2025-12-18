package com.example.paperfx.server;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;

/**
 * Состояние одного подключённого TCP-клиента на сервере.
 * <p>
 * Содержит сокет, потоки ввода/вывода, а также кэш авторизации/профиля/статистики.
 */

final class ClientConn implements Closeable {
    final ServerMain server;
    final Socket socket;
    final BufferedReader in;
    final PrintWriter out;

    volatile boolean authed = false;
    volatile String userId;
    volatile String username;

    volatile String roomId = "MAIN";
    volatile String playerId;
    volatile boolean spectator = false;

    volatile long lastChatMs = 0;

    // ---- кэш профиля/статистики (загружается при логине) ----
    // Итоговые значения за всё время (из БД + накопленные дельты в памяти)
    volatile long killsTotal = 0;
    volatile long areaTotal = 0;

    // Лучшие значения (кэш из БД / текущей сессии)
    volatile int bestScore = 0;          // максимальный счёт за всё время (столбец best_score в app_users)
    volatile int bestKillsInGame = 0;    // максимум убийств за одну игру/сессию
    volatile int bestKillStreak = 0;     // максимальная серия убийств за всё время

    // Накопленные дельты для записи в БД (батчами)
    volatile long pendingKills = 0;
    volatile long pendingArea = 0;
    volatile boolean statsDirty = false;

    // Текущая игровая сессия (с момента входа в игру игроком)
    volatile int sessionKills = 0;
    volatile int currentKillStreak = 0;
    volatile int sessionMaxKillStreak = 0;
    volatile int sessionMaxScore = 0;

    // Кэш достижений (чтобы не делать лишние записи в БД)
    final java.util.Set<String> unlockedAchievements = java.util.concurrent.ConcurrentHashMap.newKeySet();

    volatile long lastStatsFlushMs = 0;
    /**
     * Создаёт соединение с клиентом и поднимает потоки ввода/вывода.
     */

    ClientConn(ServerMain server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    void start() {
        Thread t = new Thread(this::run, "client-" + socket.getPort());
        t.setDaemon(true);
        t.start();
    }
    /**
     * Основной цикл чтения: читает JSONL строки из сокета и передаёт на обработку серверу.
     */

    void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode n = Net.parse(line);
                String type = n.path("type").asText("");
                server.onMessage(this, type, n);
            }
        } catch (Exception ignored) {
        } finally {
            server.onDisconnected(this);
        }
    }

    void send(String jsonLine) {
        try { out.println(jsonLine); } catch (Exception ignored) {}
    }
    /**
     * Отправляет JSON-объект клиенту одной строкой.
     */

    void sendJson(ObjectNode node) {
        try { send(Net.MAPPER.writeValueAsString(node)); } catch (Exception ignored) {}
    }

    @Override public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }
}