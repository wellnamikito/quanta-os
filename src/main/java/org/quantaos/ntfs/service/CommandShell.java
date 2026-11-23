package org.quantaos.ntfs.service;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.quantaos.process.MultilevelFeedbackScheduler;
import org.quantaos.process.ProcessSnapshot;

/**
 * Minimal command interpreter described in section 1.3 of the specification.
 */
public final class CommandShell {
    private final Scanner input;
    private final PrintStream output;
    private final NtfsVolume volume = new NtfsVolume();
    /** Планировщик процессов не зависит от того, смонтирован ли учебный том. */
    private final MultilevelFeedbackScheduler scheduler =
            new MultilevelFeedbackScheduler();

    /**
     * Создаёт оболочку с заданными потоками: так её можно использовать и в
     * консоли, и в тестах.
     *
     * @param input источник строк команд
     * @param output поток сообщений пользователю
     */
    public CommandShell(InputStream input, PrintStream output) {
        this.input = new Scanner(input);
        this.output = output;

        /*
         * Как и в настоящей ОС, планировщик не начинает работу с пустой таблицей:
         * PID 1 — системный init, PID 2 — интерактивная оболочка. Файловые
         * команды выполняются синхронно внутри shell, а долгие учебные задачи
         * пользователь создаёт явной командой run.
         */
        scheduler.create("init", 0, 0);
        scheduler.create("quanta-shell", 1, 0);
    }
    /**
     * Запускает цикл чтения команд до {@code exit}, {@code quit} или конца
     * входного потока. Ошибки отдельной команды выводятся пользователю и не
     * завершают оболочку.
     */
    public void run() {
        output.println("DONNTU NTFS emulator. Введите help для справки.");
        while (true) {
            output.print(volume.isMounted()
                    ? (volume.isLoggedIn() ? "ntfs$ " : "ntfs(login)$ ")
                    : "ntfs(unmounted)$ ");
            if (!input.hasNextLine())
                return;
            String line = input.nextLine().trim();
            if (line.isEmpty())
                continue;
            try {
                if (execute(line))
                    return;
            } catch (Exception e) {
                output.println("Ошибка: " + e.getMessage());
            }
        }
    }
    /**
     * Разбирает одну введённую строку и вызывает соответствующий системный вызов
     * тома. Соответствует набору команд из пункта 1.3 проектирования.
     *
     * @param line строка, введённая пользователем
     * @return {@code true}, если интерпретатор должен завершить работу
     */
    boolean execute(String line) throws Exception {
        List<String> a = words(line);
        String cmd = a.getFirst().toLowerCase();
        switch (cmd) {
            case "help" ->
                    output.println(
                            "format <file> <bytes> <admin> <password>; mount <file>; unmount; "
                                    + "login/logout; useradd <login> <password> <group>; usermod -g <group> <login>; userdel "
                                    + "<login>; groupadd <name>; mkfile|touch <path>; mkdir <path>; append <path> <text>; cat <path>; "
                                    + "copy|cp <source> <destination>; move|mv <source> <destination>; rename <path> <name>; "
                                    + "delete|rm <path>; chmod <path> <xyz>; chown <user> <path>; chgrp <group> <path>; ls [-l] [path]; cd <path>; pwd; id [user]; "
                                    + "info; hexdump <offset> <bytes>; run|procadd <name> <absolute 0..2> "
                                    + "[relative -2..2]; nice <relative -2..2> <name> <absolute 0..2>; "
                                    + "tick [count]; ps; block|sleep/wake/kill <pid>; "
                                    + "renice <relative -2..2> <pid>; scheduler; exit");
            case "format" -> {
                need(a, 5);
                volume.format(Path.of(a.get(1)), Long.parseLong(a.get(2)), a.get(3),
                        a.get(4));
                output.println("Образ отформатирован");
            }
            case "mount" -> {
                need(a, 2);
                volume.mount(Path.of(a.get(1)));
                output.println("Образ смонтирован");
            }
            case "unmount" -> {
                volume.unmount();
                output.println("Образ размонтирован");
            }
            case "login" -> {
                need(a, 3);
                volume.login(a.get(1), a.get(2));
                output.println("Вход выполнен");
            }
            case "logout" -> {
                volume.logout();
                output.println("Выход выполнен");
            }
            case "groupadd" -> {
                need(a, 2);
                volume.addGroup(a.get(1));
                output.println("Группа создана");
            }
            case "useradd" -> {
                need(a, 4);
                volume.addUser(a.get(1), a.get(2), a.get(3));
                output.println("Пользователь создан");
            }
            case "userdel" -> {
                need(a, 2);
                volume.blockUser(a.get(1));
                output.println("Пользователь заблокирован; его файлы сохранены");
            }
            case "usermod" -> {
                need(a, 4);
                if (!a.get(1).equals("-g"))
                    throw new IllegalArgumentException("Использование: usermod -g <group> <login>");
                volume.changeUserGroup(a.get(3), a.get(2));
                output.println("Основная группа пользователя изменена");
            }
            case "mkfile", "touch" -> {
                need(a, 2);
                volume.create(a.get(1), false);
            }
            case "mkdir" -> {
                need(a, 2);
                volume.create(a.get(1), true);
            }
            case "append" -> {
                need(a, 3);
                volume.append(a.get(1), a.get(2));
            }
            case "cat" -> {
                need(a, 2);
                output.println(volume.read(a.get(1)));
            }
            case "copy", "cp" -> {
                need(a, 3);
                volume.copy(a.get(1), a.get(2));
            }
            case "move", "mv" -> {
                need(a, 3);
                volume.move(a.get(1), a.get(2));
            }
            case "rename" -> {
                need(a, 3);
                volume.rename(a.get(1), a.get(2));
            }
            case "delete", "rm" -> {
                need(a, 2);
                volume.delete(a.get(1));
            }
            case "chmod" -> {
                need(a, 3);
                volume.chmod(a.get(1), a.get(2));
            }
            case "chown" -> {
                need(a, 3);
                volume.chown(a.get(2), a.get(1));
                output.println("Владелец изменён");
            }
            case "chgrp" -> {
                need(a, 3);
                volume.chgrp(a.get(2), a.get(1));
                output.println("Группа файла изменена");
            }
            case "ls" -> list(a);
            case "cd" -> {
                need(a, 2);
                volume.cd(a.get(1));
            }
            case "pwd" -> output.println(volume.pwd());
            case "id" -> {
                if (a.size() > 2)
                    throw new IllegalArgumentException("Использование: id [user]");
                output.println(a.size() == 2 ? volume.identity(a.get(1)) : volume.identity());
            }
            case "info" -> output.println(volume.info());
            case "hexdump" -> {
                need(a, 3);
                output.println(volume.hexDump(Long.parseLong(a.get(1)), Integer.parseInt(a.get(2))));
            }
            case "procadd", "run" -> {
                need(a, 3);
                int relative = a.size() >= 4 ? Integer.parseInt(a.get(3)) : 0;
                int pid = scheduler.create(a.get(1), Integer.parseInt(a.get(2)), relative);
                output.println("Процесс создан: PID=" + pid);
            }
            case "nice" -> {
                need(a, 4);
                int pid = scheduler.create(a.get(2), Integer.parseInt(a.get(3)),
                        Integer.parseInt(a.get(1)));
                output.println("Процесс создан с nice=" + a.get(1) + ": PID=" + pid);
            }
            case "tick" -> {
                int count = a.size() >= 2 ? Integer.parseInt(a.get(1)) : 1;
                scheduler.tick(count).forEach(output::println);
            }
            case "ps" -> {
                output.println("PID NAME STATE ABS REL DYN CPU WAIT");
                for (ProcessSnapshot p : scheduler.snapshot())
                    output.printf("%d %s %s %d %d %d %d %d%n", p.pid(), p.name(),
                            p.state(), p.absolutePriority(), p.relativePriority(),
                            p.dynamicPriority(), p.executedTicks(), p.waitedTicks());
            }
            case "block", "sleep" -> {
                need(a, 2);
                scheduler.block(Integer.parseInt(a.get(1)));
            }
            case "wake" -> {
                need(a, 2);
                scheduler.wake(Integer.parseInt(a.get(1)));
            }
            case "kill" -> {
                need(a, 2);
                scheduler.terminate(Integer.parseInt(a.get(1)));
            }
            case "renice" -> {
                need(a, 3);
                // Linux-подобный порядок: renice <relative> <pid>.
                scheduler.setRelativePriority(Integer.parseInt(a.get(2)),
                        Integer.parseInt(a.get(1)));
            }
            case "scheduler" -> output.println(scheduler.currentProcess());
            case "exit", "quit" -> {
                if (volume.isMounted())
                    volume.unmount();
                return true;
            }
            default -> output.println("Неизвестная команда: " + cmd);
        }
        return false;
    }
    /**
     * Проверяет, что команда получила минимально необходимое число аргументов.
     */
    private static void need(List<String> a, int n) {
        if (a.size() < n)
            throw new IllegalArgumentException("Недостаточно параметров");
    }
    /**
     * Разбирает Linux-подобные варианты {@code ls}, {@code ls path},
     * {@code ls -l} и {@code ls -l path}.
     */
    private void list(List<String> arguments) {
        boolean longFormat = arguments.size() >= 2 && arguments.get(1).equals("-l");
        if (arguments.size() > (longFormat ? 3 : 2))
            throw new IllegalArgumentException("Использование: ls [-l] [path]");
        String path = longFormat
                ? (arguments.size() == 3 ? arguments.get(2) : ".")
                : (arguments.size() == 2 ? arguments.get(1) : ".");
        if (longFormat)
            output.println("MODE       LINKS OWNER        GROUP           SIZE MODIFIED          NAME");
        (longFormat ? volume.listLong(path) : volume.list(path)).forEach(output::println);
    }
    /**
     * Делит строку на аргументы, не разделяя текст, заключённый в двойные
     * кавычки. Например, {@code append a.txt "два слова"} передаёт текст одним
     * аргументом.
     */
    private static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!s.isEmpty()) {
                    out.add(s.toString());
                    s.setLength(0);
                }
            } else
                s.append(c);
        }
        if (quoted)
            throw new IllegalArgumentException("Незакрытая кавычка");
        if (!s.isEmpty())
            out.add(s.toString());
        return out;
    }
}
