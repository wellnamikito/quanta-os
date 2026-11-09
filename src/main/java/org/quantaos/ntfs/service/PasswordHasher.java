package org.quantaos.ntfs.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PasswordHasher {
    /** Запрещает создание объекта утилитного класса. */
    private PasswordHasher() {}
    /** Пункт 1.1.7: пароль сохраняется и сравнивается только как SHA-256-хеш. */
    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
