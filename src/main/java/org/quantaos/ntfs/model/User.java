package org.quantaos.ntfs.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 1.7.7: строка системной таблицы $Users.
 *
 * @param login имя пользователя
 * @param passwordHash SHA-256 хэш пароля, но не пароль в открытом виде
 * @param uid уникальный идентификатор пользователя
 * @param gid идентификатор основной группы
 * @param role уровень полномочий пользователя
 */
public record User(
        String login,
        String passwordHash,
        int uid,
        int gid,
        Role role
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
