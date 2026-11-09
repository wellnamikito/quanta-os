package org.quantaos.ntfs.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Пункт 1.1.1 проектирования: параметры логического суперблока.
 *
 * <p>В реальной файловой системе эта структура помещается в первый сектор 512
 * байт. В эмуляторе она хранится как объект, но поля соответствуют
 * таблице 1.2.</p>
 *
 * @param signature сигнатура формата тома
 * @param sectorSize размер сектора в байтах
 * @param clusterSize размер кластера в байтах
 * @param clusterCount общее число кластеров
 * @param diskSize логический размер тома в байтах
 * @param mftStartCluster начало области MFT
 * @param mftRecordCount количество записей MFT
 * @param mftRecordSize размер одной записи MFT
 * @param mirrorStartCluster начало копии первых записей MFT
 * @param bitmapStartCluster начало карты занятости кластеров
 * @param bitmapSize размер bitmap в байтах
 * @param rootRecord номер MFT корневого каталога
 * @param dataStartCluster начало пользовательской области данных
 * @param bTreeDegree минимальная степень B-деревьев каталогов
 * @param volumeSerial случайный серийный номер тома
 */
public record
Superblock(String signature, int sectorSize, int clusterSize,
           int clusterCount, long diskSize, int mftStartCluster,
           int mftRecordCount, int mftRecordSize, int mirrorStartCluster,
           int bitmapStartCluster, int bitmapSize, int rootRecord,
           int dataStartCluster, int bTreeDegree, long volumeSerial)
        implements Serializable {
    /** Версия класса для механизма Java-сериализации. */
    @Serial private static final long serialVersionUID = 1L;
    /**
     * Константы проекта: 512-байтный сектор, 1024-байтная MFT и B-дерево
     * порядка 15.
     */
    public static final int SECTOR_SIZE = 512, MFT_RECORD_SIZE = 1024,
            BTREE_DEGREE = 15;
}