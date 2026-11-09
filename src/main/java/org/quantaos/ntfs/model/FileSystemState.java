package org.quantaos.ntfs.model;

import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.util.BitSet;

import java.util.*;

/**
 * Логическое содержимое образа файловой системы.
 *
 * <p>Пункты 1.1.3–1.1.4: {@code allocatedClusters} моделирует файл $Bitmap,
 * а {@code mft} — главную файловую таблицу. Номер записи в списке совпадает с
 * её номером MFT.</p>
 */
@Getter
public class FileSystemState implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final Superblock superblock;
    private final BitSet allocatedClusters;
    private final List<MftEntry> mft;
    // Область данных: храним только реально записанные кластеры, а не весь образ
    // в памяти.
    private final Map<Integer, byte[]> clusterData = new HashMap<>();
    // Пункт 1.1.7: логические системные файлы $Users и $Groups.
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Group> groups = new HashMap<>();

    /**
     * Создаёт пустой том и заранее выделяет все записи MFT.
     * Сами записи остаются свободными до форматирования или создания файла.
     *
     * @param superblock параметры создаваемого тома
     */
    public FileSystemState(Superblock superblock) {
        this.superblock = superblock;
        this.allocatedClusters = new BitSet(superblock.clusterCount());
        this.mft = new ArrayList<>(superblock.mftRecordCount());
        for (int i = 0; i < superblock.mftRecordCount(); i++)
            mft.add(new MftEntry(i));
    }
    /**
     * Находит запись MFT по её номеру и проверяет границы таблицы.
     *
     * @param record номер записи MFT
     * @return запись MFT, даже если она пока свободна
     */
    public MftEntry entry(int record) {
        if (record < 0 || record >= mft.size())
            throw new IllegalArgumentException("Некорректный номер MFT: " + record);
        return mft.get(record);
    }
}
