package org.quantaos;


import org.quantaos.ntfs.service.CommandShell;

/**
 * Точка входа учебного эмулятора NTFS-подобной файловой системы.
 * Запускает только интерфейс; вся логика находится в {@code NtfsVolume}.
 */
public final class Main {
    /** Запрещает создание экземпляров класса запуска. */
    private Main() {}

    /**
     * Точка входа программы: создаёт и запускает командный интерпретатор.
     *
     * @param args аргументы командной строки; в текущей версии не используются
     */
    public static void main(String[] args) {
        new CommandShell(System.in, System.out).run();
    }
}
