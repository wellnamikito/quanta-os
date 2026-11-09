package org.quantaos.ntfs.service;

import org.quantaos.ntfs.model.FileSystemState;
import org.quantaos.ntfs.model.Superblock;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Записывает учебный образ файловой системы как обычный бинарный файл.
 *
 * <p>Первый сектор содержит суперблок по фиксированным смещениям, поэтому
 * сигнатуру, размер кластера и границы областей видно в hex-редакторе.
 * Служебное Java-состояние находится в зарезервированной области MFT, а каждый
 * нерезидентный кластер записывается по адресу {@code cluster * clusterSize}.
 * Это не формат Windows NTFS, а физически наблюдаемая модель по проекту.</p>
 */
public final class VolumeStore {
    /** Метка служебного снимка в области MFT. */
    private static final byte[] STATE_MAGIC = "QNTSTAT1".getBytes(StandardCharsets.US_ASCII);
    /** Размер первого сектора, который содержит суперблок. */
    private static final int SUPERBLOCK_BYTES = 512;

    /**
     * Сохраняет полный образ во временный файл и атомарно заменяет старый.
     * Благодаря этому образ не остаётся наполовину записанным при сбое.
     */
    public void save(Path path, FileSystemState state) throws IOException {
        Path target = path.toAbsolutePath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Superblock sb = state.getSuperblock();
        byte[] metadata = serialize(state);
        long metadataOffset = clusterOffset(sb.mftStartCluster(), sb.clusterSize());
        long metadataCapacity = clusterOffset(sb.mirrorStartCluster(), sb.clusterSize())
                - metadataOffset;
        if (metadata.length + STATE_MAGIC.length + Integer.BYTES > metadataCapacity)
            throw new IOException("Служебное состояние не помещается в область MFT");

        try (RandomAccessFile image = new RandomAccessFile(temp.toFile(), "rw")) {
            image.setLength(sb.diskSize());
            image.seek(0);
            image.write(encodeSuperblock(sb));
            image.seek(metadataOffset);
            image.write(STATE_MAGIC);
            image.writeInt(metadata.length);
            image.write(metadata);
            writeClusters(image, state.getClusterData(), sb.clusterSize());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Монтирует образ: сперва читает физический суперблок, затем служебное
     * состояние и, наконец, фактические байты нерезидентных кластеров.
     */
    public FileSystemState load(Path path) throws IOException {
        try (RandomAccessFile image = new RandomAccessFile(path.toFile(), "r")) {
            Superblock superblock = decodeSuperblock(readExactly(image, SUPERBLOCK_BYTES));
            long metadataOffset = clusterOffset(superblock.mftStartCluster(),
                    superblock.clusterSize());
            image.seek(metadataOffset);
            byte[] magic = readExactly(image, STATE_MAGIC.length);
            if (!Arrays.equals(magic, STATE_MAGIC))
                throw new IOException("В образе нет служебной области DONNTU NTFS");
            int metadataLength = image.readInt();
            long maxLength = clusterOffset(superblock.mirrorStartCluster(),
                    superblock.clusterSize()) - metadataOffset - STATE_MAGIC.length - Integer.BYTES;
            if (metadataLength < 0 || metadataLength > maxLength)
                throw new IOException("Повреждён размер служебной области образа");
            FileSystemState state = deserialize(readExactly(image, metadataLength));
            if (state.getSuperblock().diskSize() != superblock.diskSize())
                throw new IOException("Суперблок и служебное состояние не совпадают");
            readClusters(image, state.getClusterData(), superblock.clusterSize());
            return state;
        }
    }

    /** Формирует первые 512 байт образа в little-endian порядке. */
    private byte[] encodeSuperblock(Superblock sb) {
        ByteBuffer buffer = ByteBuffer.allocate(SUPERBLOCK_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        byte[] signature = sb.signature().getBytes(StandardCharsets.US_ASCII);
        buffer.put(Arrays.copyOf(signature, 8));
        buffer.putShort((short) sb.sectorSize());
        buffer.putInt(sb.clusterSize());
        buffer.putInt(sb.clusterCount());
        buffer.putLong(sb.diskSize());
        buffer.putInt(sb.mftStartCluster());
        buffer.putInt(sb.mftRecordCount());
        buffer.putShort((short) sb.mftRecordSize());
        buffer.putInt(sb.mirrorStartCluster());
        buffer.putInt(sb.bitmapStartCluster());
        buffer.putInt(sb.bitmapSize());
        buffer.putInt(sb.rootRecord());
        buffer.putInt(sb.dataStartCluster());
        buffer.putShort((short) sb.bTreeDegree());
        buffer.putLong(sb.volumeSerial());
        return buffer.array();
    }

    /** Восстанавливает суперблок из первого сектора образа. */
    private Superblock decodeSuperblock(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] signature = new byte[8];
        buffer.get(signature);
        String text = new String(signature, StandardCharsets.US_ASCII).replace("\0", "").trim();
        if (!"DONNTFS".equals(text))
            throw new IOException("Это не образ DONNTU NTFS");
        return new Superblock(text, Short.toUnsignedInt(buffer.getShort()), buffer.getInt(),
                buffer.getInt(), buffer.getLong(), buffer.getInt(), buffer.getInt(),
                Short.toUnsignedInt(buffer.getShort()), buffer.getInt(), buffer.getInt(),
                buffer.getInt(), buffer.getInt(), buffer.getInt(),
                Short.toUnsignedInt(buffer.getShort()), buffer.getLong());
    }

    /** Записывает все выделенные нерезидентные кластеры в их физические позиции. */
    private void writeClusters(RandomAccessFile image, Map<Integer, byte[]> clusters,
                               int clusterSize) throws IOException {
        for (Map.Entry<Integer, byte[]> entry : clusters.entrySet()) {
            image.seek(clusterOffset(entry.getKey(), clusterSize));
            image.write(Arrays.copyOf(entry.getValue(), clusterSize));
        }
    }

    /** Перечитывает кластеры из файла, чтобы изменения hex-редактора были видны. */
    private void readClusters(RandomAccessFile image, Map<Integer, byte[]> clusters,
                              int clusterSize) throws IOException {
        for (Integer cluster : clusters.keySet()) {
            image.seek(clusterOffset(cluster, clusterSize));
            clusters.put(cluster, readExactly(image, clusterSize));
        }
    }

    /** Сериализует объекты Java в служебной области того же бинарного образа. */
    private byte[] serialize(FileSystemState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(state);
        }
        return bytes.toByteArray();
    }

    /** Десериализует служебную часть образа. */
    private FileSystemState deserialize(byte[] bytes) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (FileSystemState) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Несовместимое служебное состояние образа", e);
        }
    }

    /** Читает строго указанное число байт или сообщает о неполном образе. */
    private byte[] readExactly(RandomAccessFile image, int count) throws IOException {
        byte[] bytes = new byte[count];
        image.readFully(bytes);
        return bytes;
    }

    /** Вычисляет физическое смещение кластера в файле образа. */
    private long clusterOffset(int cluster, int clusterSize) {
        return (long) cluster * clusterSize;
    }
}
