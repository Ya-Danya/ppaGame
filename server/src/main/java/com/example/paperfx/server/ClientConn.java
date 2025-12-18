package com.example.paperfx.server;

import com.example.paperfx.common.Net;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.net.InetSocketAddress;

/**
 * Сессия клиента поверх UDP.
 *
 * Каждая сессия идентифицируется удалённым UDP-адресом (IP:порт).
 * Сервер принимает датаграммы и вызывает {@link #onDatagram(String)}.
 */
final class ClientConn implements Closeable {
    final ServerMain server;
    final InetSocketAddress addr;

    volatile long lastSeenMs = System.currentTimeMillis();

    volatile boolean authed = false;
    volatile String userId;
    volatile String username;

    volatile String roomId = "MAIN";
    volatile String playerId;
    volatile boolean spectator = false;

    volatile long lastChatMs = 0;

    // ---- кэш профиля/статистики (загружается при входе) ----
    // Итоговые значения за всё время (из БД + накопленные дельты в памяти)
    volatile long killsTotal = 0;
    volatile long areaTotal = 0;

    // Лучшие значения (кэш из БД / из текущей сессии)
    volatile int bestScore = 0;          // лучший счёт за всё время (best_score в app_users)
    volatile int bestKillsInGame = 0;    // максимум убийств за одну игру/сессию
    volatile int bestKillStreak = 0;     // лучший стрик убийств за всё время

    // Накопленные дельты для пакетной записи в БД
    volatile long pendingKills = 0;
    volatile long pendingArea = 0;
    volatile boolean statsDirty = false;

    // Текущая игровая сессия (с момента последнего входа как игрок)
    volatile int sessionKills = 0;
    volatile int currentKillStreak = 0;
    volatile int sessionMaxKillStreak = 0;
    volatile int sessionMaxScore = 0;

    // Кэш достижений (чтобы избегать лишних записей в БД)
    final java.util.Set<String> unlockedAchievements = java.util.concurrent.ConcurrentHashMap.newKeySet();

    volatile long lastStatsFlushMs = 0;

    ClientConn(ServerMain server, InetSocketAddress addr) {
        this.server = server;
        this.addr = addr;
    }

    void touch() {
        lastSeenMs = System.currentTimeMillis();
    }

    void onDatagram(String jsonLine) {
        touch();
        JsonNode n = null;
        try {
            n = Net.parse(jsonLine);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        String type = n.path("type").asText("");
        server.onMessage(this, type, n);
    }

    void send(String jsonLine) {
        server.sendTo(addr, jsonLine);
    }

    void sendJson(ObjectNode node) {
        try { send(Net.MAPPER.writeValueAsString(node)); } catch (Exception ignored) {}
    }

    @Override public void close() {
        // В UDP-режиме нет отдельного сокета на клиента — закрывать нечего.
    }
}
