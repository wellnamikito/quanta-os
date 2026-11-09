package org.quantaos.ntfs.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Пункт 1.7.7: строка системной таблицы $Groups
 *
 * @param name имя группы
 * @param gid уникальный идентификатор группы
 * @param creatorUid идентификатор пользователя, создавшего группу
 */
public record Group(
        String name,
        int gid,
        int creatorUid
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
