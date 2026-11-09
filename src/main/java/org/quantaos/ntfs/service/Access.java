package org.quantaos.ntfs.service;

import org.quantaos.ntfs.model.MftEntry;
import org.quantaos.ntfs.model.Role;
import org.quantaos.ntfs.model.User;

/**
 * Пункт 1.1.7: проверка девяти битов rwx для владельца, группы и остальных.
 * Администратор проходит проверку без ограничений
 */
public final class Access {
    /** Биты чтения, записи и выполнения внутри одной октальной тройки прав */
    public static final int READ = 4, WRITE = 2, EXECUTE = 1;

    /** Запрещает создание объекта утилитного класса. */

    /**
     * Выбирает права владельца, группы или остальных и проверяет запршенные биты.
     *
     * @param user пользователь текущего сеанса
     * @param entry объект, к которому запрашивается доступ
     * @param requested комбинация {@link #READ}, {@link #WRITE}, {@link #EXECUTE}
     * @return {@code true}, если операция разрешена
     */
    public static boolean allows(User user, MftEntry entry, int requested){
        if(user.role() == Role.ADMIN){
            return true;
        }
        int bits = user.uid() == entry.getOwnerUid() ? entry.getPermissions() >> 6
                : user.gid() == entry.getGroupGid() ? entry.getPermissions() >> 3
                                                    : entry.getPermissions();
        return (bits & requested) == requested;
    }

    /**
     * Проверяет доступ и выбрасывает понятную ошибку, если он запрещён.
     *
     * @throws SecurityException если пользователь не обладает запрошенными правами.
     */
    public static void require(User user, MftEntry entry, int requested){
        if (!allows(user, entry, requested)){
            throw new SecurityException("Доступ запрещён: " + entry.getName());
        }
    }
}
