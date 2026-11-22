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
 * Командный интерпретатор учебной операционной системы QuantaOS.
 *
 * <p>Оболочка принимает команды пользователя, передаёт операции файловой
 * системы объекту {@link NtfsVolume} и управляет учебным планировщиком
 * процессов.</p>
 */
public final class CommandShell {
    private final Scanner input;
    private final PrintStream output;
    private final NtfsVolume volume = new NtfsVolume();

    /**
     * Планировщик процессов MLFQ. Он существует независимо от состояния
     * смонтированного образа файловой системы.
     */
    private final MultilevelFeedbackScheduler scheduler =
            new MultilevelFeedbackScheduler();

    /**
     * Создаёт оболочку с заданными потоками ввода и вывода.
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
        output.println("QuantaOS emulator. Введите help для справки.");

        while (true) {
            output.print(volume.isMounted()
                    ? (volume.isLoggedIn() ? "quanta$ " : "quanta(login)$ ")
                    : "quanta(unmounted)$ ");

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
     * @param line введённая пользователем команда
     * @return true, если оболочку необходимо завершить
     * @throws Exception при ошибке файловой системы или ввода-вывода
     */
    boolean execute(String line) throws Exception {
        List<String> args = words(line);
        String command = args.getFirst().toLowerCase();

        switch (command) {
            case "help" -> output.println(helpText());

            case "format" -> {
                need(args, 5);

                volume.format(
                        Path.of(args.get(1)),
                        Long.parseLong(args.get(2)),
                        args.get(3),
                        args.get(4)
                );

                output.println("Образ отформатирован");
            }

            case "mount" -> {
                need(args, 2);

                volume.mount(Path.of(args.get(1)));
                output.println("Образ смонтирован");
            }

            case "unmount" -> {
                volume.unmount();
                output.println("Образ размонтирован");
            }

            case "login" -> {
                need(args, 3);

                volume.login(args.get(1), args.get(2));
                output.println("Вход выполнен");
            }

            case "logout" -> {
                volume.logout();
                output.println("Выход выполнен");
            }

            case "groupadd" -> {
                need(args, 2);

                volume.addGroup(args.get(1));
                output.println("Группа создана");
            }

            case "useradd" -> {
                need(args, 4);

                volume.addUser(
                        args.get(1),
                        args.get(2),
                        args.get(3)
                );

                output.println("Пользователь создан");
            }

            case "userdel" -> {
                need(args, 2);

                volume.blockUser(args.get(1));
                output.println("Пользователь заблокирован; его файлы сохранены");
            }

            case "mkfile" -> {
                need(args, 2);

                volume.create(args.get(1), false);
                output.println("Файл создан");
            }

            case "mkdir" -> {
                need(args, 2);

                volume.create(args.get(1), true);
                output.println("Каталог создан");
            }

            case "append" -> {
                need(args, 3);

                volume.append(args.get(1), args.get(2));
                output.println("Данные добавлены");
            }

            case "cat" -> {
                need(args, 2);

                output.println(volume.read(args.get(1)));
            }

            case "copy" -> {
                need(args, 3);

                volume.copy(args.get(1), args.get(2));
                output.println("Копирование выполнено");
            }

            case "move" -> {
                need(args, 3);

                volume.move(args.get(1), args.get(2));
                output.println("Перемещение выполнено");
            }

            case "rename" -> {
                need(args, 3);

                volume.rename(args.get(1), args.get(2));
                output.println("Переименование выполнено");
            }

            case "delete" -> {
                need(args, 2);

                volume.delete(args.get(1));
                output.println("Объект удалён");
            }

            case "chmod" -> {
                need(args, 3);

                volume.chmod(args.get(1), args.get(2));
                output.println("Права доступа изменены");
            }

            case "ls" -> volume.list(
                    args.size() == 1 ? "." : args.get(1)
            ).forEach(output::println);

            case "cd" -> {
                need(args, 2);

                volume.cd(args.get(1));
            }

            case "pwd" -> output.println(volume.pwd());

            case "info" -> output.println(volume.info());

            case "hexdump" -> {
                need(args, 3);

                output.println(
                        volume.hexDump(
                                Long.parseLong(args.get(1)),
                                Integer.parseInt(args.get(2))
                        )
                );
            }

            case "procadd" -> {
                need(args, 3);

                int relativePriority = args.size() >= 4
                        ? Integer.parseInt(args.get(3))
                        : 0;

                int pid = scheduler.create(
                        args.get(1),
                        Integer.parseInt(args.get(2)),
                        relativePriority
                );

                output.println("Процесс создан: PID=" + pid);
            }

            case "tick" -> {
                int count = args.size() >= 2
                        ? Integer.parseInt(args.get(1))
                        : 1;

                scheduler.tick(count).forEach(output::println);
            }

            case "ps" -> {
                output.println("PID NAME STATE ABS REL DYN CPU WAIT");

                for (ProcessSnapshot process : scheduler.snapshot()) {
                    output.printf(
                            "%d %s %s %d %d %d %d %d%n",
                            process.pid(),
                            process.name(),
                            process.state(),
                            process.absolutePriority(),
                            process.relativePriority(),
                            process.dynamicPriority(),
                            process.executedTicks(),
                            process.waitedTicks()
                    );
                }
            }

            case "block" -> {
                need(args, 2);

                scheduler.block(Integer.parseInt(args.get(1)));
                output.println("Процесс заблокирован");
            }

            case "wake" -> {
                need(args, 2);

                scheduler.wake(Integer.parseInt(args.get(1)));
                output.println("Процесс разблокирован");
            }

            case "kill" -> {
                need(args, 2);

                scheduler.terminate(Integer.parseInt(args.get(1)));
                output.println("Процесс завершён");
            }

            case "renice" -> {
                need(args, 3);

                scheduler.setRelativePriority(
                        Integer.parseInt(args.get(1)),
                        Integer.parseInt(args.get(2))
                );

                output.println("Относительный приоритет изменён");
            }

            case "scheduler" -> output.println(
                    scheduler.currentProcess()
            );

            case "exit", "quit" -> {
                if (volume.isMounted())
                    volume.unmount();

                return true;
            }

            default -> output.println(
                    "Неизвестная команда: " + command
            );
        }

        return false;
    }

    /**
     * Формирует многострочную справку по всем командам эмулятора.
     *
     * @return текст справки для команды help
     */
    private static String helpText() {
        return """
                
                ===== QuantaOS: справка =====
                
                ОБРАЗ ФАЙЛОВОЙ СИСТЕМЫ
                  format <file> <bytes> <admin> <password>
                      Создать и отформатировать образ.
                  
                  mount <file>
                      Смонтировать существующий образ.
                  
                  unmount
                      Сохранить изменения и размонтировать образ.
                  
                  info
                      Показать параметры суперблока.
                  
                  hexdump <offset> <bytes>
                      Показать байты образа в шестнадцатеричном виде.
                
                
                ПОЛЬЗОВАТЕЛИ И ГРУППЫ
                  login <login> <password>
                      Войти в систему.
                  
                  logout
                      Завершить текущий сеанс.
                  
                  groupadd <name>
                      Создать группу. Доступно администратору.
                  
                  useradd <login> <password> <group>
                      Создать пользователя и назначить ему одну группу.
                  
                  userdel <login>
                      Заблокировать пользователя, сохранив его файлы.
                
                
                ФАЙЛЫ И КАТАЛОГИ
                  mkfile <path>
                      Создать пустой файл.
                  
                  mkdir <path>
                      Создать каталог.
                  
                  append <path> <text>
                      Добавить текст в конец файла.
                  
                  cat <path>
                      Прочитать содержимое файла.
                  
                  ls [path]
                      Показать содержимое текущего или указанного каталога.
                  
                  cd <path>
                      Перейти в каталог.
                  
                  pwd
                      Показать текущий путь.
                  
                  copy <path> <directory>
                      Скопировать файл или каталог.
                  
                  move <path> <directory>
                      Переместить файл или каталог.
                  
                  rename <path> <new-name>
                      Переименовать файл или каталог.
                  
                  delete <path>
                      Удалить файл или каталог рекурсивно.
                  
                  chmod <path> <xyz>
                      Изменить права доступа, например: chmod file.txt 640.
                
                
                ПРОЦЕССЫ И ПЛАНИРОВЩИК MLFQ
                  procadd <name> <absolute 0..2> [relative -2..2]
                      Создать процесс.
                      0 — высокий, 1 — обычный, 2 — низкий приоритет.
                  
                  tick [count]
                      Выполнить один или несколько тактов планировщика.
                  
                  ps
                      Показать таблицу процессов и их приоритеты.
                  
                  block <pid>
                      Заблокировать процесс, например при ожидании ввода-вывода.
                  
                  wake <pid>
                      Разблокировать процесс и вернуть его в очередь готовых.
                  
                  kill <pid>
                      Завершить процесс.
                  
                  renice <pid> <relative>
                      Изменить относительный приоритет процесса.
                  
                  scheduler
                      Показать текущий процесс, очередь и остаток кванта.
                
                
                ПРОЧЕЕ
                  help
                      Показать эту справку.
                  
                  exit
                      Сохранить образ и завершить программу.
                """;
    }

    /**
     * Проверяет минимальное число аргументов команды.
     *
     * @param args список аргументов команды
     * @param required минимальное требуемое число аргументов
     */
    private static void need(List<String> args, int required) {
        if (args.size() < required) {
            throw new IllegalArgumentException(
                    "Недостаточно параметров"
            );
        }
    }

    /**
     * Делит строку на аргументы, сохраняя текст в двойных кавычках
     * единым аргументом.
     *
     * @param line строка команды
     * @return список аргументов
     */
    private static List<String> words(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder currentWord = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char symbol = line.charAt(i);

            if (symbol == '"') {
                quoted = !quoted;
                continue;
            }

            if (Character.isWhitespace(symbol) && !quoted) {
                if (!currentWord.isEmpty()) {
                    result.add(currentWord.toString());
                    currentWord.setLength(0);
                }
            } else {
                currentWord.append(symbol);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException(
                    "Незакрытая кавычка"
            );
        }

        if (!currentWord.isEmpty()) {
            result.add(currentWord.toString());
        }

        return result;
    }
}