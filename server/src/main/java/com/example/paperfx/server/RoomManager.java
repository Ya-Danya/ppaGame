package com.example.paperfx.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class RoomManager {
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final int capacity;
    private final int gridW;
    private final int gridH;
    private final int cellSize;

    RoomManager(int capacity, int gridW, int gridH, int cellSize) {
        this.capacity = capacity;
        this.gridW = gridW;
        this.gridH = gridH;
        this.cellSize = cellSize;

        // default room
        rooms.put("MAIN", new Room("MAIN", capacity, gridW, gridH, cellSize));
    }

    Room get(String roomId) { return rooms.get(roomId); }

    Room create() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String id = Ids.roomId();
            Room r = new Room(id, capacity, gridW, gridH, cellSize);
            if (rooms.putIfAbsent(id, r) == null) return r;
        }
        // fallback
        Room r = new Room(Ids.roomId(), capacity, gridW, gridH, cellSize);
        rooms.put(r.roomId, r);
        return r;
    }

    Iterable<Room> allRooms() { return rooms.values(); }
}
