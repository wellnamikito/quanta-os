package org.quantaos.ntfs.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Пункт 1.1.5: непрерывный диапозон кластеров в нерезидентном атрибуте DATA.
 *
 * @param startCluster LCN первого кластера экстента
 * @param length число кластеров в экстенте
 */
public record Extent(
        int startCluster,
        int length
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Возвращает номер первого кластера после экстента.
     * Нужен для проверки, что два экстента можно объединить в один
     */
    public int endExclusive(){
        return startCluster + length;
    }
}
