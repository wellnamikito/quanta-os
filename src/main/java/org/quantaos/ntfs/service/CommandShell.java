package org.quantaos.ntfs.service;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Командный интерпретатор учебной NTFS-подобной файловой системы.
 */
public final class CommandShell {
    private final Scanner input;
    private final PrintStream output;
    private final NtfsVolume volume = new NtfsVolume();

    /**
     * Создаёт оболочку с заданными потоками.
     *
     * @param input источник строк команд
     * @param output поток сообщений пользователю
     */
    public CommandShell(InputStream input, PrintStream output) {
        this.input = new Scanner(input);
        this.output = output;
    }

    /**
     * Запускает цикл чтения команд до exit, quit или конца входного потока.
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
     * Разбирает одну введённую строку и вызывает соответствующую операцию.
     *
     * @param line введённая строка
     * @return true, если оболочку нужно завершить
     */
    boolean execute(String line) throws Exception {
        List<String> a = words(line);
        String cmd = a.getFirst().toLowerCase();

        switch (cmd) {
            case "help" ->
                    output.println(
                            "format <file> <bytes> <admin> <password>; "
                                    + "mount <file>; unmount; login/logout; "
                                    + "useradd <login> <password> <group>; "
                                    + "userdel <login>; groupadd <name>; "
                                    + "mkfile/mkdir <path>; append <path> <text>; "
                                    + "cat <path>; copy <path> <dir>; "
                                    + "move <path> <dir>; rename <path> <name>; "
                                    + "delete <path>; chmod <path> <xyz>; "
                                    + "ls [path]; cd <path>; pwd; info; "
                                    + "hexdump <offset> <bytes>; exit");

            case "format" -> {
                need(a, 5);
                volume.format(
                        Path.of(a.get(1)),
                        Long.parseLong(a.get(2)),
                        a.get(3),
                        a.get(4)
                );
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

            case "mkfile" -> {
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

            case "copy" -> {
                need(a, 3);
                volume.copy(a.get(1), a.get(2));
            }

            case "move" -> {
                need(a, 3);
                volume.move(a.get(1), a.get(2));
            }

            case "rename" -> {
                need(a, 3);
                volume.rename(a.get(1), a.get(2));
            }

            case "delete" -> {
                need(a, 2);
                volume.delete(a.get(1));
            }

            case "chmod" -> {
                need(a, 3);
                volume.chmod(a.get(1), a.get(2));
            }

            case "ls" ->
                    volume.list(a.size() == 1 ? "." : a.get(1))
                            .forEach(output::println);

            case "cd" -> {
                need(a, 2);
                volume.cd(a.get(1));
            }

            case "pwd" -> output.println(volume.pwd());

            case "info" -> output.println(volume.info());

            case "hexdump" -> {
                need(a, 3);
                output.println(
                        volume.hexDump(
                                Long.parseLong(a.get(1)),
                                Integer.parseInt(a.get(2))
                        )
                );
            }

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
     * Проверяет минимальное число аргументов команды.
     *
     * @param a список аргументов
     * @param n минимальное требуемое число аргументов
     */
    private static void need(List<String> a, int n) {
        if (a.size() < n)
            throw new IllegalArgumentException("Недостаточно параметров");
    }

    /**
     * Разделяет строку на аргументы, сохраняя текст в двойных кавычках
     * единым аргументом.
     *
     * @param line строка команды
     * @return список аргументов
     */
    private static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                quoted = !quoted;
                continue;
            }

            if (Character.isWhitespace(c) && !quoted) {
                if (!s.isEmpty()) {
                    out.add(s.toString());
                    s.setLength(0);
                }
            } else {
                s.append(c);
            }
        }

        if (quoted)
            throw new IllegalArgumentException("Незакрытая кавычка");

        if (!s.isEmpty())
            out.add(s.toString());

        return out;
    }
}