package org.quantaos.ntfs;
import org.quantaos.ntfs.service.*;


import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
    /**
     * Проверяет, что первый сектор действительно читается как бинарный
     * суперблок, а блокировка пользователя сохраняется после размонтирования.
     */
    @Test
    void storesBinarySuperblockAndBlocksInsteadOfDeletingUser() throws Exception {
        Path image = dir.resolve("raw.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        assertTrue(fs.hexDump(0, 8).contains("44 4F 4E 4E 54 46 53"));
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.blockUser("alice");
        fs.unmount();
        fs.mount(image);
        assertThrows(SecurityException.class, () -> fs.login("alice", "pw"));
    }
    /**
     * Проверяет Linux-подобный сценарий {@code cp file file-copy} в одном
     * каталоге и формат строки {@code ls -l}.
     */
    @Test
    void copiesToNewNameInsideSameDirectoryAndPrintsLongListing() throws Exception {
        Path image = dir.resolve("copy.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.create("docs", true);
        fs.create("docs/note.txt", false);
        fs.append("docs/note.txt", "copy me");
        fs.copy("docs/note.txt", "docs/note-copy.txt");
        assertEquals("copy me", fs.read("docs/note-copy.txt"));
        assertEquals(List.of("note-copy.txt", "note.txt"), fs.list("docs"));
        fs.move("docs/note-copy.txt", "docs/note-moved.txt");
        assertEquals("copy me", fs.read("docs/note-moved.txt"));
        assertEquals(List.of("note-moved.txt", "note.txt"), fs.list("docs"));
        assertTrue(fs.listLong("docs").getFirst().matches(
                "-rw-r--r-- 1 admin +admin +\\s+7 .+ note-moved\\.txt"));
    }
    /** Проверяет, что закрытый потомок нельзя прочитать через рекурсивный copy. */
    @Test
    void deniesRecursiveCopyWhenNestedFileIsUnreadable() throws Exception {
        Path image = dir.resolve("copy-rights.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.create("source", true);
        fs.create("source/secret.txt", false);
        fs.chmod("source/secret.txt", "600");
        fs.create("target", true);
        fs.chmod("target", "777");
        fs.logout();
        fs.login("alice", "pw");
        assertThrows(SecurityException.class, () -> fs.copy("/source", "/target/copy"));
    }
    /** Проверяет явное ограничение размера учебного образа. */
    @Test
    void rejectsImageLargerThanConfiguredLimit() {
        NtfsVolume fs = new NtfsVolume();
        assertThrows(IllegalArgumentException.class, () -> fs.format(
                dir.resolve("too-large.ntfs"), NtfsVolume.MAX_IMAGE_SIZE + 1,
                "admin", "secret"));
    }
    /**
     * Проверяет одну основную группу пользователя, смену группы, личный каталог
     * и административную смену владельца и группы файла.
     */
    @Test
    void managesPrimaryGroupsHomesAndFileOwnership() throws Exception {
        Path image = dir.resolve("users.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.addGroup("students");
        fs.addGroup("teachers");
        fs.addUser("alice", "pw", "students");
        assertTrue(fs.identity("alice").contains("students"));
        assertEquals(List.of("alice"), fs.list("/home"));
        fs.changeUserGroup("alice", "teachers");
        assertTrue(fs.identity("alice").contains("teachers"));
        fs.logout();
        fs.login("alice", "pw");
        fs.create("/home/alice/note.txt", false);
        fs.append("/home/alice/note.txt", "private");
        fs.logout();
        fs.login("admin", "secret");
        fs.chown("/home/alice/note.txt", "admin");
        fs.chgrp("/home/alice/note.txt", "students");
        assertTrue(fs.listLong("/home/alice").getFirst().contains("admin"));
        assertTrue(fs.listLong("/home/alice").getFirst().contains("students"));
    }

    /**
     * Один образ нельзя открыть двумя экземплярами одновременно: иначе второй
     * экземпляр мог бы сохранить устаревший снимок поверх новых данных первого.
     */
    @Test
    void preventsConcurrentMountAndReleasesLockAfterUnmount() throws Exception {
        Path image = dir.resolve("locked-volume.ntfs");
        NtfsVolume first = new NtfsVolume();
        NtfsVolume second = new NtfsVolume();
        first.format(image, 64L * 1024 * 1024, "admin", "secret");
        assertThrows(IOException.class, () -> second.mount(image));
        first.unmount();
        second.mount(image);
        second.login("admin", "secret");
        assertEquals("uid=0(admin) gid=0(admin) groups=0(admin) role=ADMIN blocked=false",
                second.identity());
        second.unmount();
    }

    /** Форматирование создаёт администратора, но не выполняет вход от его имени. */
    @Test
    void formatCreatesAdminButRequiresExplicitLogin() throws Exception {
        NtfsVolume fs = new NtfsVolume();
        fs.format(dir.resolve("first-admin.ntfs"), 64L * 1024 * 1024,
                "admin", "secret");
        assertFalse(fs.isLoggedIn());
        assertThrows(SecurityException.class, () -> fs.list("/"));
        fs.login("admin", "secret");
        assertTrue(fs.identity().contains("uid=0(admin)"));
        fs.unmount();
    }

    /**
     * Проверяет owner/group/other: группа может читать файл, но не изменять, а
     * посторонний пользователь не может даже прочитать его.
     */
    @Test
    void enforcesOwnerGroupAndOtherPermissionsAndKeepsBlockedUserFiles()
            throws Exception {
        Path image = dir.resolve("access.ntfs");
        NtfsVolume fs = new NtfsVolume();
        fs.format(image, 64L * 1024 * 1024, "admin", "secret");
        fs.login("admin", "secret");
        fs.addGroup("students");
        fs.addUser("alice", "alice-pass", "students");
        fs.addUser("bob", "bob-pass", "students");
        fs.addUser("charlie", "charlie-pass", "users");

        fs.logout();
        fs.login("alice", "alice-pass");
        fs.create("/home/alice/share", true);
        fs.create("/home/alice/share/report.txt", false);
        fs.append("/home/alice/share/report.txt", "group can read");
        fs.chmod("/home/alice", "750");
        fs.chmod("/home/alice/share", "750");
        fs.chmod("/home/alice/share/report.txt", "640");

        fs.logout();
        fs.login("bob", "bob-pass");
        assertEquals("group can read", fs.read("/home/alice/share/report.txt"));
        assertThrows(SecurityException.class,
                () -> fs.append("/home/alice/share/report.txt", "blocked edit"));

        fs.logout();
        fs.login("charlie", "charlie-pass");
        assertThrows(SecurityException.class,
                () -> fs.read("/home/alice/share/report.txt"));

        fs.logout();
        fs.login("admin", "secret");
        fs.blockUser("alice");
        fs.unmount();
        fs.mount(image);
        assertThrows(SecurityException.class, () -> fs.login("alice", "alice-pass"));
        fs.login("admin", "secret");
        assertEquals("group can read", fs.read("/home/alice/share/report.txt"));
        fs.unmount();
    }

    /** Проверяет копирование в существующий каталог и запрет перезаписи файла. */
    @Test
    void copiesIntoExistingDirectoryAndRejectsOverwrite() throws Exception {
        NtfsVolume fs = new NtfsVolume();
        fs.format(dir.resolve("copy-directory.ntfs"), 64L * 1024 * 1024,
                "admin", "secret");
        fs.login("admin", "secret");
        fs.create("docs", true);
        fs.create("backup", true);
        fs.create("docs/source.txt", false);
        fs.append("docs/source.txt", "copy check");
        fs.copy("docs/source.txt", "backup");
        assertEquals("copy check", fs.read("backup/source.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> fs.copy("docs/source.txt", "backup"));
        fs.unmount();
    }

    /**
     * В Unix создание и удаление зависят от прав каталога, а не будущего файла:
     * без {@code w+x} пользователь не может добавить запись в каталог.
     */
    @Test
    void deniesCreationInsideDirectoryWithoutWritePermission() throws Exception {
        NtfsVolume fs = new NtfsVolume();
        fs.format(dir.resolve("directory-rights.ntfs"), 64L * 1024 * 1024,
                "admin", "secret");
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.create("locked", true);
        fs.chmod("locked", "555");
        fs.logout();

        fs.login("alice", "pw");
        assertThrows(SecurityException.class, () -> fs.create("/locked/new.txt", false));
        fs.unmount();
    }

    /**
     * Владелец тоже подчиняется установленным им правам: {@code r--} не даёт
     * дописывать файл, хотя файл принадлежит текущему пользователю.
     */
    @Test
    void deniesAppendWhenOwnerRemovesOwnWritePermission() throws Exception {
        NtfsVolume fs = new NtfsVolume();
        fs.format(dir.resolve("owner-rights.ntfs"), 64L * 1024 * 1024,
                "admin", "secret");
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.logout();

        fs.login("alice", "pw");
        fs.create("/home/alice/read-only.txt", false);
        fs.append("/home/alice/read-only.txt", "first version");
        fs.chmod("/home/alice/read-only.txt", "400");
        assertThrows(SecurityException.class,
                () -> fs.append("/home/alice/read-only.txt", "forbidden edit"));
        fs.unmount();
    }

    /** Только администратор имеет право передать файл другому владельцу. */
    @Test
    void deniesChownForOrdinaryUser() throws Exception {
        NtfsVolume fs = new NtfsVolume();
        fs.format(dir.resolve("chown-rights.ntfs"), 64L * 1024 * 1024,
                "admin", "secret");
        fs.login("admin", "secret");
        fs.addUser("alice", "pw", "users");
        fs.logout();

        fs.login("alice", "pw");
        fs.create("/home/alice/owned.txt", false);
        assertThrows(SecurityException.class,
                () -> fs.chown("/home/alice/owned.txt", "admin"));
        fs.unmount();
    }
}

