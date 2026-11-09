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
 * @param blocked признак блокировки: пользователь не может начать новый сеанс
 */
public record User(
        String login,
        String passwordHash,
        int uid,
        int gid,
        Role role,
        boolean blocked
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Создает копию записи с новым состоянием блокировки.
     * Record неизменяемя, поэтому поля нельзя изменить в уже созданном объекте
     *
     * @param blocked новое состояние блокировки
     * @return обновлённая запись пользователя
     */
    public User withBlocked(boolean blocked){
        return new User(login, passwordHash, uid, gid, role, blocked);
    }
}
