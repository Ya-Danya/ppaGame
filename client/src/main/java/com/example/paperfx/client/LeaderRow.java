package com.example.paperfx.client;

import javafx.beans.property.*;

/**
 * Модель строки лидерборда (таблица рекордов).
 */

public final class LeaderRow {
    private final StringProperty username = new SimpleStringProperty();
    private final IntegerProperty score = new SimpleIntegerProperty();

    public LeaderRow(String username, int score) {
        this.username.set(username);
        this.score.set(score);
    }

    public StringProperty usernameProperty() { return username; }
    public IntegerProperty scoreProperty() { return score; }
}