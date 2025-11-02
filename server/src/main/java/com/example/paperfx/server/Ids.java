package com.example.paperfx.server;

import java.security.SecureRandom;

final class Ids {
    private Ids() {}
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    static String roomId() {
        char[] c = new char[6];
        for (int i = 0; i < c.length; i++) c[i] = ALPH[RNG.nextInt(ALPH.length)];
        return new String(c);
    }

    static String playerId() {
        // simple id
        char[] c = new char[10];
        for (int i = 0; i < c.length; i++) c[i] = ALPH[RNG.nextInt(ALPH.length)];
        return new String(c);
    }
}
