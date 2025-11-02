package com.example.paperfx.server;

import com.example.paperfx.common.Messages;

import java.util.ArrayList;
import java.util.List;

final class PlayerEntity {
    final String playerId;
    final String userId;
    final String username;
    int idx;

    double x;
    double y;
    int dx;
    int dy;

    final String color;
    boolean drawing = false;
    final List<Messages.Cell> trail = new ArrayList<>();

    PlayerEntity(String playerId, String userId, String username, String color) {
        this.playerId = playerId;
        this.userId = userId;
        this.username = username;
        this.color = color;
    }
}
