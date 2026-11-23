package org.quantaos.ntfs.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет Linux-подобные алиасы и формат вывода команды ls -l. */
class CommandShellTest {
    @TempDir Path dir;

    @Test
    void supportsLinuxAliasesAndLongDirectoryListing() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CommandShell shell = new CommandShell(new ByteArrayInputStream(new byte[0]),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        Path image = dir.resolve("shell.ntfs");

        shell.execute("format " + image + " 67108864 admin secret");
        shell.execute("login admin secret");
        shell.execute("touch note.txt");
        shell.execute("append note.txt \"two words\"");
        shell.execute("cp note.txt copy.txt");
        shell.execute("mv copy.txt moved.txt");
        shell.execute("ls -l");
        shell.execute("rm moved.txt");
        shell.execute("unmount");

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("MODE       LINKS OWNER"));
        assertTrue(output.contains("note.txt"));
        assertTrue(output.contains("moved.txt"));
        assertFalse(output.contains("Неизвестная команда"));
    }

    /**
     * Таблица процессов не пуста при запуске: PID 1 моделирует init, а PID 2 —
     * интерактивную оболочку. Команда run создаёт пользовательскую задачу.
     */
    @Test
    void showsSystemProcessesAndSupportsLinuxStyleRunAndNiceCommands()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CommandShell shell = new CommandShell(new ByteArrayInputStream(new byte[0]),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        shell.execute("ps");
        shell.execute("run compiler 1");
        shell.execute("nice -1 editor 1");
        shell.execute("renice 2 3");
        shell.execute("sleep 3");
        shell.execute("wake 3");
        shell.execute("tick 1");

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("init"));
        assertTrue(output.contains("quanta-shell"));
        assertTrue(output.contains("Процесс создан: PID=3"));
        assertTrue(output.contains("Процесс создан с nice=-1: PID=4"));
        assertFalse(output.contains("Неизвестная команда"));
    }
}
