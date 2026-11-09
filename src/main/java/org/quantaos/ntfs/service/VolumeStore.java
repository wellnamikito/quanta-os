package org.quantaos.ntfs.service;

import org.quantaos.ntfs.model.FileSystemState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Сохраняет смоделированные структуры в файл образа.
 *
 * <p>Это осознанное отличие от физического размещения секторов из пункта 1.1:
 * Java-сериализация нужна только для учебного эмулятора и не делает образ
 * совместимым с Windows NTFS.</p>
 */
public final class VolumeStore {
    /** Сигнатура в начале файла, отличающая образ от произвольного файла Java. */
    private static final String MAGIC = "DONNTU_NTFS_1";

    /**
     * Сохраняет состояние тома через временный файл, затем атомарно заменяет
     * образ. Такой порядок снижает риск оставить повреждённый образ при сбое
     * записи.
     *
     * @param path путь к файлу образа
     * @param state сохраняемое состояние тома
     */
    public void save(Path path, FileSystemState state) throws IOException {
        Path target = path.toAbsolutePath(),
                temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeUTF(MAGIC);
            out.writeObject(state);
        }
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    /**
     * Проверяет сигнатуру и восстанавливает сохранённое состояние тома.
     *
     * @param path путь к существующему файлу образа
     * @return содержимое смонтированного тома
     */
    public FileSystemState load(Path path) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            if (!MAGIC.equals(in.readUTF()))
                throw new IOException("Это не образ DONNTU NTFS");
            try {
                return (FileSystemState)in.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Несовместимый образ", e);
            }
        }
    }
}
