package com.example.paperfx.common;

import java.util.List;

public final class Messages {
    private Messages() {}

    public static final class Cell {
        public int x;
        public int y;
        public Cell(int x, int y) { this.x = x; this.y = y; }
        public Cell() {}
    }

    // client -> server
    public static final class Hello {
        public final String type = "hello";
        public String name;
        public Hello(String name) { this.name = name; }
    }

    public static final class Input {
        public final String type = "input";
        public int dx;
        public int dy;
        public Input(int dx, int dy) { this.dx = dx; this.dy = dy; }
    }

    // server -> client
    public static final class Welcome {
        public final String type = "welcome";
        public String id;
        public int idx;
        public String color;
        public Welcome(String id, int idx, String color) {
            this.id = id; this.idx = idx; this.color = color;
        }
    }

    public static final class Player {
        public String id;
        public int idx;
        public String name;
        public double x;
        public double y;
        public int score; // points for NEWLY captured cells
        public String color;
        public List<Cell> trail;

        public Player(String id, int idx, String name, double x, double y, int score, String color, List<Cell> trail) {
            this.id = id; this.idx = idx; this.name = name;
            this.x = x; this.y = y;
            this.score = score; this.color = color;
            this.trail = trail;
        }
        public Player() {}
    }

    public static final class State {
        public final String type = "state";
        public long tick;

        public int cellSize;
        public int gridW;
        public int gridH;
        public int[] owners;

        public List<Player> players;

        public State(long tick, int cellSize, int gridW, int gridH, int[] owners, List<Player> players) {
            this.tick = tick;
            this.cellSize = cellSize;
            this.gridW = gridW;
            this.gridH = gridH;
            this.owners = owners;
            this.players = players;
        }
        public State() {}
    }
}
