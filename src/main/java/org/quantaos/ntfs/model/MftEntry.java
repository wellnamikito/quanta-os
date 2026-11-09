package org.quantaos.ntfs.model;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Пункты 1.1.4 - 1.1.5: одна логическая запись MFT размером 1024 байта
 *
 * <p> Поля описывают заголовок и обязательные атрибуты: STANDARD_INFORMATION,
 * FILE_NAME, DATA и, для каталогов, INDEX_ROOT / INDEX_ALLOCATION / BITMAP. </p>
 *
 * <p> Аннотация из плагина Lombok {@code @Getter} и {@code @Setter} автоматически
 * создают методы чтения и изменения полей, поэтому их не приходится писать вручнуюю</p>
 */

@Getter
@Setter
public class MftEntry implements Serializable {

    @Serial
    private static final Long serialVersionUID = 1L;

    // Неизменяемый номер записи, используемый как идентификатор файла.
    private final int recordNumber;

    // Счетчик повторного использования записи после удаления.
    private int generation;

    //Флаг занятости записи
    private boolean used;

    // Признак каталога; иначе запись представляет обычнй файл.
    private boolean directory;

    // Признак системного метафайла
    private boolean system;

    // Имя объекта в каталоге родителя
    private String name;

    // Номер MFT родительского каталога.
    private int parentRecord;

    // UID владельца
    private int ownerUid;

    // GIG основной группы объекта
    private int groupGid;

    // Девять младших битов: права rwx для владельца, группы и остальных пользователей
    private int permissions;

    // Времена создания, изменения и последнего чтения из STANDARD_INFORMATION
    private Instant createdAt = Instant.now(),
                    modifiedAt = Instant.now(),
                    accessedAt = Instant.now();

    // DATA хранится прямо в MFT, пока размер не превышает 785 байт.
    private byte[] residentData = new byte[0];

    // Реальный размер пользовательских данных в байтах.
    private long fileSize;

    // Нерезидентный DATA: упорядоченный список пар "начальный кластер; длина".
    private final List<Extent> extents = new ArrayList<>();

    // Каталог хранит индекс имён, а обычный файл - null
    private BTree<String, Integer> directoryIndex;


    /**
     * Создает свободную запись с неизменяем номером MFT.
     *
     * @param recordNumber индекс записиь в MFT
     */
    public MftEntry(int recordNumber) {
        this.recordNumber = recordNumber;
    }

    /**
     * Обновляет время модификации после изменения данных или метаданных файла
     */
    public void touchModified(){
        modifiedAt = Instant.now();
    }


}
