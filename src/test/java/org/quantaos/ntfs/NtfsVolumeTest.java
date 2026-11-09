package org.quantaos.ntfs;
import org.quantaos.ntfs.service.*;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Автоматические проверки ключевых сценариев из проектирования файловой
 * системы.
 */
class NtfsVolumeTest {
    @TempDir Path dir;
    /**
     * Проверяет резидентный DATA, его перенос в кластеры и поиск имени без учёта
     * регистра.
     */
    @Test
    void persistsDataPromotesResidentFileAndUsesCaseInsensitiveDirectoryIndex()
            throws Exception {
        Path image = dir.resolve("volume.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.create("docs", true);
        fs.create("docs/Readme.txt", false);
        fs.append("docs/readme.TXT", "x".repeat(900));
        assertEquals("x".repeat(900), fs.read("docs/README.txt"));
        fs.unmount();
        fs.mount(image);
        fs.login("admin", "secret");
        assertEquals("x".repeat(900), fs.read("/docs/readme.txt"));
        assertTrue(fs.list("/docs").getFirst().endsWith("Readme.txt"));
    }
    /** Проверяет права rwx и рекурсивное удаление непустого каталога. */
    @Test
    void enforcesPermissionsAndRecursivelyDeletesDirectories() throws Exception {
        Path image = dir.resolve("volume.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.create("private", true);
        fs.chmod("private", "700");
        fs.create("private/a", false);
        fs.logout();
        fs.login("alice", "pw");
        assertThrows(SecurityException.class, () -> fs.list("/private"));
        fs.logout();
        fs.login("admin", "secret");
        fs.delete("private");
        assertFalse(fs.list("/").stream().anyMatch(x -> x.endsWith("private")));
    }
    /**
     * Проверяет разделение полного B-дерева и сохранение его структуры в образе.
     */
    @Test
    void splitsDirectoryBTreeAndKeepsEntriesAfterRemount() throws Exception {
        Path image = dir.resolve("tree.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        for (int i = 0; i < 40; i++)
            fs.create(String.format("f%02d", i), false);
        assertEquals(40, fs.list("/").size());
        fs.unmount();
        fs.mount(image);
        fs.login("admin", "secret");
        assertEquals(40, fs.list("/").size());
        assertEquals("", fs.read("f39"));
    }
}

