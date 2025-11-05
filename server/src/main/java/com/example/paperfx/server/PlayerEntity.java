package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.util.ArrayList;
import java.util.HashSet;

final class PlayerEntity {
    final String userId;
    final String username;
    final String playerId;
    final int idx;
    final String color;

    double x, y;
    int inputDx = 0, inputDy = 0;

    int score = 0;
    int matchKills = 0;
    int killStreakCurrent = 0;
    int killStreakMax = 0;
    int cellX, cellY;
    int deadCooldownTicks = 0;

    final HashSet<Long> trailSet = new HashSet<>();
    final ArrayList<Messages.Cell> trailList = new ArrayList<>();

    PlayerEntity(String userId, String username, String playerId, int idx, String color,
                 double x, double y, int cellX, int cellY) {
        this.userId = userId;
        this.username = username;
        this.playerId = playerId;
        this.idx = idx;
        this.color = color;
        this.x = x;
        this.y = y;
        this.cellX = cellX;
        this.cellY = cellY;
    }

    void clearTrail() { trailSet.clear(); trailList.clear(); }
}
