package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Игровая сущность игрока внутри комнаты: позиция, ввод, след, счёт и цвет.
 * <p>
 * Создаётся на сервере при входе игроком в комнату.
 */

final class PlayerEntity {
    final String userId;
    final String username;
    final String playerId;
    final int idx;
    final String color;
    final ClientConn conn;

    double x, y;
    int inputDx = 0, inputDy = 0;

    int score = 0;
    int cellX, cellY;
    int deadCooldownTicks = 0;

    final HashSet<Long> trailSet = new HashSet<>();
    final ArrayList<Messages.Cell> trailList = new ArrayList<>();

    PlayerEntity(String userId, String username, String playerId, int idx, String color, ClientConn conn,
                 double x, double y, int cellX, int cellY) {
        this.userId = userId;
        this.username = username;
        this.playerId = playerId;
        this.idx = idx;
        this.color = color;
        this.conn = conn;
        this.x = x;
        this.y = y;
        this.cellX = cellX;
        this.cellY = cellY;
    }

    void clearTrail() { trailSet.clear(); trailList.clear(); }
}