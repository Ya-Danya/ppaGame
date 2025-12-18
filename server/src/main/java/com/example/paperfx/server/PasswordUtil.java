package com.example.paperfx.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;


/**
* Утилиты для безопасного хранения паролей.
 * Используется PBKDF2-HMAC-SHA256 с солью и сравнением в постоянное время.
*/
public final class PasswordUtil {
    private PasswordUtil() {}

    private static final SecureRandom RNG = new SecureRandom();/**
* Генерирует криптографически стойкую соль (16 байт) и возвращает её в Base64.
*/


    public static String newSaltBase64() {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }/**
* Вычисляет PBKDF2-HMAC-SHA256 от пароля с указанной солью.
 * @param password пароль (в открытом виде)
 * @param saltBase64 соль в Base64
 * @return хэш в Base64
*/


    public static String pbkdf2Base64(String password, String saltBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("hash error: " + e.getMessage(), e);
        }
    }/**
* Сравнение строк в (приблизительно) постоянное время, чтобы уменьшить риск тайминговых атак.
*/


    public static boolean slowEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        int diff = x.length ^ y.length;
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) diff |= (x[i] ^ y[i]);
        return diff == 0;
    }
}
