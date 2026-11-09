package org.quantaos.ntfs.service;

import org.quantaos.ntfs.model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Реализация системных вызовов из пунктов 1.4 и 1.6 проектирования.
 *
 * <p>Публичные методы соответствуют операциям FS_FORMAT, FS_MOUNT, USER_LOGIN,
 * MFT_ALLOC, CLUSTER_ALLOC, BTREE_FIND, FILE_CREATE, FILE_APPEND и другим.
 * Командная оболочка вызывает именно эти методы, а не изменяет структуры
 * напрямую.</p>
 */
public final class NtfsVolume {
    public static final int ROOT_RECORD = 3, USERS_RECORD = 4, GROUPS_RECORD = 5,
            FIRST_USER_RECORD = 16, RESIDENT_LIMIT = 785,
            MAX_EXTENTS = 96;
    private final VolumeStore store = new VolumeStore();
    private FileSystemState state;
    private Path imagePath;
    private User session;
    private int currentDirectory = ROOT_RECORD;

    /**
     * FS_FORMAT: создаёт новый пустой образ, рассчитывает служебные области и
     * создаёт метафайлы $MFT, $MFTMirr, $Bitmap, $Users и $Groups.
     *
     * @param image путь к создаваемому образу
     * @param bytes логический размер тома в байтах
     * @param adminLogin логин первого администратора
     * @param password пароль первого администратора; в образ попадёт только его
     *     хеш
     */
    public void format(Path image, long bytes, String adminLogin, String password)
            throws IOException {
        // 1.1.2: расчёт C, N, B и количества записей MFT по формулам главы.
        if (bytes < 8L * 1024 * 1024)
            throw new IllegalArgumentException("Минимальный размер образа — 8 МиБ");
        if (adminLogin.isBlank() || adminLogin.length() > 15)
            throw new IllegalArgumentException("Логин: 1..15 символов");
        int sector = Superblock.SECTOR_SIZE, cluster = clusterSize(bytes, sector),
                count = (int)(bytes / cluster), mftCount = (int)(bytes / 8192);
        int mftClusters =
                ceil((long)mftCount * Superblock.MFT_RECORD_SIZE, cluster),
                mirrorClusters = ceil(6L * Superblock.MFT_RECORD_SIZE, cluster),
                bitmapBytes = ceil(count, 8),
                bitmapClusters = ceil(bitmapBytes, cluster);
        int mftStart = 1, mirrorStart = mftStart + mftClusters,
                bitmapStart = mirrorStart + mirrorClusters,
                dataStart = bitmapStart + bitmapClusters;
        if (mftCount < FIRST_USER_RECORD || dataStart >= count)
            throw new IllegalArgumentException(
                    "Размер образа слишком мал для служебных структур");
        Superblock sb = new Superblock(
                "DONNTFS", sector, cluster, count, bytes, mftStart, mftCount, 1024,
                mirrorStart, bitmapStart, bitmapBytes, ROOT_RECORD, dataStart, 15,
                new SecureRandom().nextLong());
        state = new FileSystemState(sb);
        imagePath = image.toAbsolutePath();

        // 1.1.3: служебная область занята сразу после форматирования.
        state.getAllocatedClusters().set(0, dataStart);

        // 1.1.4: первые 16 MFT-записей зарезервированы для метафайлов.
        systemEntry(0, "$MFT", false);
        systemEntry(1, "$MFTMirr", false);
        systemEntry(2, "$Bitmap", false);
        MftEntry root = systemEntry(ROOT_RECORD, ".", true);
        root.setPermissions(0755);
        root.setOwnerUid(0);
        root.setGroupGid(0);
        systemEntry(USERS_RECORD, "$Users", false);
        systemEntry(GROUPS_RECORD, "$Groups", false);
        state.getGroups().put("admin", new Group("admin", 0, 0));
        state.getGroups().put("users", new Group("users", 1, 0));
        state.getUsers().put(adminLogin,
                new User(adminLogin, PasswordHasher.sha256(password),
                        0, 0, Role.ADMIN));
        save();
    }
    /**
     * FS_MOUNT: загружает ранее сохранённый образ и сбрасывает незавершённый
     * сеанс.
     *
     * @param image путь к существующему образу
     */
    public void mount(Path image) throws IOException {
        state = store.load(image.toAbsolutePath());
        imagePath = image.toAbsolutePath();
        session = null;
        currentDirectory = ROOT_RECORD;
    }
    /**
     * FS_UNMOUNT: сохраняет все изменения и освобождает объект смонтированного
     * тома.
     */
    public void unmount() throws IOException {
        ensureMounted();
        save();
        state = null;
        session = null;
    }
    /**
     * USER_LOGIN: аутентифицирует пользователя и переводит его в корневой
     * каталог.
     *
     * @throws SecurityException если логин не существует или пароль неверен
     */
    public void login(String login, String password) {
        // 1.6 USER_LOGIN: сверяем SHA-256 хеш и создаём пользовательский сеанс.
        ensureMounted();
        User u = state.getUsers().get(login);
        if (u == null || !u.passwordHash().equals(PasswordHasher.sha256(password)))
            throw new SecurityException("Неверный логин или пароль");
        session = u;
        currentDirectory = ROOT_RECORD;
    }
    /** USER_LOGOUT: завершает текущий сеанс без размонтирования образа. */
    public void logout() {
        session = null;
        currentDirectory = ROOT_RECORD;
    }
    /**
     * GROUP_ADD: добавляет группу с новым GID. Операция доступна только
     * администратору.
     *
     * @param name имя новой группы
     */
    public void addGroup(String name) {
        admin();
        validateName(name);
        if (state.getGroups().containsKey(name))
            throw new IllegalArgumentException("Группа уже существует");
        int gid = state.getGroups()
                .values()
                .stream()
                .mapToInt(Group::gid)
                .max()
                .orElse(0) +
                1;
        state.getGroups().put(name, new Group(name, gid, session.uid()));
        changed();
    }
    /**
     * USER_ADD: создаёт обычного пользователя и назначает ему основную группу.
     *
     * @param login уникальный логин пользователя
     * @param password исходный пароль, который немедленно хешируется
     * @param groupName имя существующей основной группы
     */
    public void addUser(String login, String password, String groupName) {
        admin();
        validateName(login);
        Group g =
                Optional.ofNullable(state.getGroups().get(groupName))
                        .orElseThrow(
                                () -> new IllegalArgumentException("Нет группы: " + groupName));
        if (state.getUsers().containsKey(login))
            throw new IllegalArgumentException("Пользователь уже существует");
        int uid =
                state.getUsers().values().stream().mapToInt(User::uid).max().orElse(
                        -1) +
                        1;
        state.getUsers().put(login, new User(login, PasswordHasher.sha256(password),
                uid, g.gid(), Role.USER));
        changed();
    }
    /**
     * FILE_CREATE: создаёт пустой файл или каталог и добавляет его имя в B-дерево
     * родителя.
     *
     * @param path абсолютный или относительный путь к новому объекту
     * @param directory {@code true} для каталога, {@code false} для обычного
     *     файла
     */
    public void create(String path, boolean directory) {
        // 1.6 FILE_CREATE: поиск родителя, проверка w+x, MFT_ALLOC и BTREE_INSERT.
        requireSession();
        Parent p = parent(path);
        Access.require(session, state.entry(p.parent),
                Access.WRITE | Access.EXECUTE);
        validateName(p.name);
        if (findChild(p.parent, p.name).isPresent())
            throw new IllegalArgumentException("Имя уже существует");
        int record = allocateMft();
        MftEntry e = state.entry(record);
        e.setUsed(true);
        e.setDirectory(directory);
        e.setSystem(false);
        e.setName(p.name);
        e.setParentRecord(p.parent);
        e.setOwnerUid(session.uid());
        e.setGroupGid(session.gid());
        e.setPermissions(directory ? 0755 : 0644);
        e.setResidentData(new byte[0]);
        e.setFileSize(0);
        e.getExtents().clear();
        if (directory)
            e.setDirectoryIndex(directoryTree());
        insertChild(p.parent, p.name, record);
        changed();
    }
    /**
     * Удобная текстовая версия FILE_APPEND: кодирует текст UTF-8 и передаёт
     * байты.
     *
     * @param path путь к файлу
     * @param text данные, добавляемые в конец
     */
    public void append(String path, String text) {
        appendBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }
    /**
     * FILE_APPEND: добавляет байты в конец файла и при необходимости переводит
     * DATA из резидентной формы в список экстентов.
     *
     * @param path путь к файлу
     * @param added данные для добавления
     */
    public void appendBytes(String path, byte[] added) {
        // 1.5–1.6 FILE_APPEND: файл является последовательным, запись идёт только в
        // конец.
        requireSession();
        MftEntry e = entryFor(path);
        if (e.isDirectory())
            throw new IllegalArgumentException("Нельзя записывать в каталог");
        Access.require(session, e, Access.WRITE);
        byte[] old = readBytes(e);
        long newSize = old.length + (long)added.length;
        if (newSize <= RESIDENT_LIMIT) {
            byte[] data = Arrays.copyOf(old, (int)newSize);
            System.arraycopy(added, 0, data, old.length, added.length);
            e.setResidentData(data);
            e.setFileSize(newSize);
            e.touchModified();
            changed();
            return;
        }
        // Если резидентный DATA переполнен, переносим весь файл в экстенты
        // кластеров.
        int needed = ceil(newSize, state.getSuperblock().clusterSize());
        List<Extent> oldExtents = new ArrayList<>(e.getExtents());
        if (!oldExtents.isEmpty())
            freeExtents(oldExtents);
        List<Extent> extents = allocateClusters(needed);
        byte[] all = Arrays.copyOf(old, (int)newSize);
        System.arraycopy(added, 0, all, old.length, added.length);
        writeNonResident(extents, all);
        e.setResidentData(new byte[0]);
        e.getExtents().clear();
        e.getExtents().addAll(extents);
        e.setFileSize(newSize);
        e.touchModified();
        changed();
    }
    /**
     * FILE_READ для текстовой оболочки: читает все байты файла и декодирует их
     * как UTF-8.
     *
     * @param path путь к файлу
     * @return текстовое содержимое файла
     */
    public String read(String path) {
        // FILE_READ: доступ проверяется до преобразования экстентов в байтовый
        // поток.
        requireSession();
        MftEntry e = entryFor(path);
        if (e.isDirectory())
            throw new IllegalArgumentException("Это каталог");
        Access.require(session, e, Access.READ);
        e.setAccessedAt(java.time.Instant.now());
        return new String(readBytes(e), StandardCharsets.UTF_8);
    }
    /**
     * Реализует команду ls: возвращает отсортированные имена из B-дерева
     * каталога.
     *
     * @param path путь к каталогу или пустая строка для текущего каталога
     * @return строки с типом объекта, правами и именем
     */
    public List<String> list(String path) {
        requireSession();
        MftEntry d = entryFor(path == null || path.isBlank() ? "." : path);
        if (!d.isDirectory())
            throw new IllegalArgumentException("Это не каталог");
        Access.require(session, d, Access.READ | Access.EXECUTE);
        return d.getDirectoryIndex()
                .entries()
                .stream()
                .map(x -> {
                    MftEntry e = state.entry(x.value());
                    return (e.isDirectory() ? "d" : "-") +
                            String.format("%03o", e.getPermissions()) + " " + x.key();
                })
                .toList();
    }
    /**
     * Реализует cd: проверяет право выполнения каталога и меняет рабочий каталог
     * сеанса.
     */
    public void cd(String path) {
        requireSession();
        MftEntry d = entryFor(path);
        if (!d.isDirectory())
            throw new IllegalArgumentException("Это не каталог");
        Access.require(session, d, Access.EXECUTE);
        currentDirectory = d.getRecordNumber();
    }
    /**
     * Реализует pwd: поднимается по ссылкам на родительские каталоги до корня.
     *
     * @return абсолютный путь текущего каталога
     */
    public String pwd() {
        ensureMounted();
        if (currentDirectory == ROOT_RECORD)
            return "/";
        Deque<String> names = new ArrayDeque<>();
        for (MftEntry e = state.entry(currentDirectory);
             e.getRecordNumber() != ROOT_RECORD;
             e = state.entry(e.getParentRecord()))
            names.push(e.getName());
        return "/" + String.join("/", names);
    }
    /**
     * FILE_CHMOD: меняет девять битов прав объекта. Изменять права может владелец
     * или admin.
     *
     * @param path путь к объекту
     * @param octal права из трёх восьмеричных цифр, например {@code 754}
     */
    public void chmod(String path, String octal) {
        // Пункт 1.1.7: в поле прав используются три восьмеричные тройки rwx.
        requireSession();
        MftEntry e = entryFor(path);
        if (session.role() != Role.ADMIN && session.uid() != e.getOwnerUid())
            throw new SecurityException(
                    "Изменить права может владелец или администратор");
        if (!octal.matches("[0-7]{3}"))
            throw new IllegalArgumentException(
                    "Права задаются тремя восьмеричными цифрами");
        e.setPermissions(Integer.parseInt(octal, 8));
        changed();
    }
    /**
     * FILE_DELETE: рекурсивно удаляет объект, освобождает его кластеры и запись
     * MFT.
     *
     * @param path путь к удаляемому файлу или каталогу
     */
    public void delete(String path) {
        // 1.6 FILE_DELETE: освобождаем экстенты, MFT-запись и ключ родительского
        // индекса.
        requireSession();
        Parent p = parent(path);
        MftEntry e =
                findChild(p.parent, p.name)
                        .map(state::entry)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Не найдено: " + path));
        Access.require(session, state.entry(p.parent),
                Access.WRITE | Access.EXECUTE);
        deleteRecord(e);
        removeChild(p.parent, p.name);
        changed();
    }
    /**
     * FILE_RENAME: изменяет ключ в B-дереве родителя, сохраняя номер MFT объекта.
     */
    public void rename(String path, String newName) {
        requireSession();
        Parent p = parent(path);
        validateName(newName);
        MftEntry e =
                findChild(p.parent, p.name)
                        .map(state::entry)
                        .orElseThrow(() -> new IllegalArgumentException("Не найдено"));
        Access.require(session, state.entry(p.parent),
                Access.WRITE | Access.EXECUTE);
        if (findChild(p.parent, newName).isPresent())
            throw new IllegalArgumentException("Имя уже существует");
        removeChild(p.parent, p.name);
        insertChild(p.parent, newName, e.getRecordNumber());
        e.setName(newName);
        changed();
    }
    /**
     * FILE_MOVE: переносит запись из индекса одного каталога в индекс другого.
     * Не позволяет сделать каталог потомком самого себя.
     */
    public void move(String path, String destinationDirectory) {
        requireSession();
        Parent p = parent(path);
        MftEntry e =
                findChild(p.parent, p.name)
                        .map(state::entry)
                        .orElseThrow(() -> new IllegalArgumentException("Не найдено"));
        MftEntry dest = entryFor(destinationDirectory);
        if (!dest.isDirectory())
            throw new IllegalArgumentException("Назначение должно быть каталогом");
        Access.require(session, state.entry(p.parent),
                Access.WRITE | Access.EXECUTE);
        Access.require(session, dest, Access.WRITE | Access.EXECUTE);
        if (findChild(dest.getRecordNumber(), p.name).isPresent())
            throw new IllegalArgumentException("Имя уже существует в назначении");
        if (e.isDirectory() &&
                isDescendant(dest.getRecordNumber(), e.getRecordNumber()))
            throw new IllegalArgumentException(
                    "Нельзя переместить каталог внутрь себя");
        removeChild(p.parent, p.name);
        insertChild(dest.getRecordNumber(), p.name, e.getRecordNumber());
        e.setParentRecord(dest.getRecordNumber());
        changed();
    }
    /**
     * FILE_COPY: рекурсивно копирует файл или каталог в заданный каталог
     * назначения.
     */
    public void copy(String source, String destinationDirectory) {
        requireSession();
        MftEntry src = entryFor(source), dest = entryFor(destinationDirectory);
        if (!dest.isDirectory())
            throw new IllegalArgumentException("Назначение должно быть каталогом");
        Access.require(session, src, Access.READ);
        Access.require(session, dest, Access.WRITE | Access.EXECUTE);
        if (findChild(dest.getRecordNumber(), src.getName()).isPresent())
            throw new IllegalArgumentException("Имя уже существует в назначении");
        copyRecord(src, dest.getRecordNumber());
        changed();
    }
    /**
     * Реализует info: формирует диагностическую строку с параметрами суперблока.
     */
    public String info() {
        ensureMounted();
        Superblock s = state.getSuperblock();
        return "signature=" + s.signature() + ", disk=" + s.diskSize() +
                " B, cluster=" + s.clusterSize() +
                " B, clusters=" + s.clusterCount() + ", free=" +
                (s.clusterCount() - state.getAllocatedClusters().cardinality()) +
                ", MFT=" + s.mftRecordCount() +
                ", dataStart=" + s.dataStartCluster() +
                ", B-tree t=" + s.bTreeDegree();
    }
    /** @return {@code true}, если образ смонтирован. */
    public boolean isMounted() { return state != null; }

    /** @return {@code true}, если в смонтированном томе есть активный сеанс. */
    public boolean isLoggedIn() { return session != null; }

    /**
     * Создаёт копию одной MFT-записи в новом родительском каталоге.
     * Для каталога рекурсивно копирует потомков, для файла — его данные.
     */
    private void copyRecord(MftEntry src, int parent) {
        create((parent == ROOT_RECORD ? "/" : pathOf(parent) + "/") + src.getName(),
                src.isDirectory());
        MftEntry made =
                findChild(parent, src.getName()).map(state::entry).orElseThrow();
        made.setPermissions(src.getPermissions());
        if (src.isDirectory())
            for (BTree.Entry<String, Integer> child :
                    src.getDirectoryIndex().entries())
                copyRecord(state.entry(child.value()), made.getRecordNumber());
        else
            appendBytes((parent == ROOT_RECORD ? "/" : pathOf(parent) + "/") +
                            src.getName(),
                    readBytes(src));
    }
    /**
     * Освобождает содержимое MFT-записи. Потомки каталога удаляются раньше самого
     * каталога.
     */
    private void deleteRecord(MftEntry e) {
        if (e.isDirectory())
            for (BTree.Entry<String, Integer> x :
                    new ArrayList<>(e.getDirectoryIndex().entries()))
                deleteRecord(state.entry(x.value()));
        if (!e.getExtents().isEmpty())
            freeExtents(e.getExtents());
        e.setUsed(false);
        e.setDirectory(false);
        e.setDirectoryIndex(null);
        e.setResidentData(new byte[0]);
        e.getExtents().clear();
        e.setFileSize(0);
        e.setGeneration(e.getGeneration() + 1);
    }
    /**
     * Проверяет, находится ли кандидат внутри каталога-предка.
     * Используется как защита от циклического перемещения каталогов.
     */
    private boolean isDescendant(int candidate, int ancestor) {
        for (int x = candidate; x != ROOT_RECORD;) {
            if (x == ancestor)
                return true;
            x = state.entry(x).getParentRecord();
        }
        return false;
    }
    /**
     * Строит абсолютный путь записи MFT, последовательно переходя к её
     * родителям.
     */
    private String pathOf(int record) {
        if (record == ROOT_RECORD)
            return "/";
        Deque<String> p = new ArrayDeque<>();
        for (MftEntry e = state.entry(record); e.getRecordNumber() != ROOT_RECORD;
             e = state.entry(e.getParentRecord()))
            p.push(e.getName());
        return "/" + String.join("/", p);
    }
    /** Разрешает путь и возвращает соответствующую занятую запись MFT. */
    private MftEntry entryFor(String path) {
        int record = resolve(path);
        return state.entry(record);
    }
    /**
     * Преобразует абсолютный или относительный путь в номер MFT, выполняя
     * BTREE_FIND для каждой части пути.
     */
    private int resolve(String path) {
        ensureMounted();
        if (path == null || path.isBlank() || path.equals("."))
            return currentDirectory;
        int r = path.startsWith("/") ? ROOT_RECORD : currentDirectory;
        for (String part : path.split("/")) {
            if (part.isBlank() || part.equals("."))
                continue;
            if (part.equals("..")) {
                r = state.entry(r).getParentRecord();
                continue;
            }
            MftEntry d = state.entry(r);
            if (!d.isDirectory())
                throw new IllegalArgumentException("Не каталог: " + d.getName());
            Access.require(session, d, Access.EXECUTE);
            // BTREE_FIND: имя следующего элемента ищется в индексе текущего каталога.
            r = findChild(r, part).orElseThrow(
                    () -> new IllegalArgumentException("Не найдено: " + path));
        }
        return r;
    }
    /**
     * Разделяет путь на номер родительского каталога и имя последнего
     * компонента.
     */
    private Parent parent(String path) {
        if (path == null || path.isBlank() || path.equals("/"))
            throw new IllegalArgumentException("Нужен путь к объекту");
        int cut = path.lastIndexOf('/');
        String base = cut < 0 ? path : path.substring(cut + 1);
        String parent = cut < 0 ? "." : cut == 0 ? "/" : path.substring(0, cut);
        return new Parent(resolve(parent), base);
    }
    /** Выполняет BTREE_FIND для имени внутри заданного каталога. */
    private Optional<Integer> findChild(int parent, String name) {
        MftEntry d = state.entry(parent);
        if (!d.isDirectory())
            throw new IllegalArgumentException("Не каталог");
        return d.getDirectoryIndex().find(name);
    }
    /** Выполняет BTREE_INSERT: добавляет имя и номер MFT в индекс каталога. */
    private void insertChild(int parent, String name, int record) {
        state.entry(parent).getDirectoryIndex().put(name, record);
    }
    /** Выполняет BTREE_REMOVE: удаляет имя из индекса родительского каталога. */
    private void removeChild(int parent, String name) {
        state.entry(parent).getDirectoryIndex().remove(name);
    }
    /**
     * Выполняет MFT_ALLOC: линейно ищет первую свободную пользовательскую запись
     * MFT. Системные записи 0–15 никогда не возвращаются.
     */
    private int allocateMft() {
        // MFT_ALLOC: пользовательские записи начинаются с номера 16.
        for (int i = FIRST_USER_RECORD; i < state.getMft().size(); i++)
            if (!state.entry(i).isUsed())
                return i;
        throw new IllegalStateException("Таблица MFT заполнена");
    }
    /**
     * Выполняет CLUSTER_ALLOC и возвращает экстенты, описывающие выделенные
     * кластеры. Сначала выбирается непрерывный участок нужного размера, иначе —
     * самый длинный.
     */
    private List<Extent> allocateClusters(int count) {
        // 1.6 CLUSTER_ALLOC: сначала берём первый непрерывный участок нужной длины,
        // иначе — самый длинный доступный участок и продолжаем поиск.
        BitSet map = state.getAllocatedClusters();
        int from = state.getSuperblock().dataStartCluster(),
                limit = state.getSuperblock().clusterCount();
        if (limit - map.cardinality() < count)
            throw new IllegalStateException("Недостаточно места");
        List<Extent> answer = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            int bestStart = -1, bestLen = 0;
            for (int i = from; i < limit;) {
                i = map.nextClearBit(i);
                if (i >= limit)
                    break;
                int end = map.nextSetBit(i);
                if (end < 0 || end > limit)
                    end = limit;
                int len = end - i;
                if (len >= remaining) {
                    bestStart = i;
                    bestLen = remaining;
                    break;
                }
                if (len > bestLen) {
                    bestStart = i;
                    bestLen = len;
                }
                i = end + 1;
            }
            if (bestStart < 0)
                throw new IllegalStateException("Недостаточно места");
            map.set(bestStart, bestStart + bestLen);
            if (!answer.isEmpty() && answer.getLast().endExclusive() == bestStart) {
                Extent prev = answer.removeLast();
                answer.add(new Extent(prev.startCluster(), prev.length() + bestLen));
            } else
                answer.add(new Extent(bestStart, bestLen));
            remaining -= bestLen;
        }
        if (answer.size() > MAX_EXTENTS) {
            freeExtents(answer);
            throw new IllegalStateException("Файл слишком фрагментирован");
        }
        return answer;
    }
    /**
     * Выполняет CLUSTER_FREE: помечает экстенты свободными и удаляет их данные из
     * памяти.
     */
    private void freeExtents(Collection<Extent> xs) {
        // CLUSTER_FREE: сбрасываем соответствующие биты $Bitmap и очищаем данные.
        for (Extent x : xs) {
            state.getAllocatedClusters().clear(x.startCluster(), x.endExclusive());
            for (int i = x.startCluster(); i < x.endExclusive(); i++)
                state.getClusterData().remove(i);
        }
    }
    /**
     * Читает DATA-атрибут: резидентный массив напрямую или кластеры в порядке
     * экстентов.
     */
    private byte[] readBytes(MftEntry e) {
        // 1.5: смещение в файле сопоставляется с кластерами в порядке экстентов.
        if (e.getExtents().isEmpty())
            return Arrays.copyOf(e.getResidentData(), e.getResidentData().length);
        byte[] out = new byte[Math.toIntExact(e.getFileSize())];
        int pos = 0, clusterSize = state.getSuperblock().clusterSize();
        for (Extent x : e.getExtents())
            for (int c = x.startCluster(); c < x.endExclusive() && pos < out.length;
                 c++) {
                byte[] b =
                        state.getClusterData().getOrDefault(c, new byte[clusterSize]);
                int n = Math.min(clusterSize, out.length - pos);
                System.arraycopy(b, 0, out, pos, n);
                pos += n;
            }
        return out;
    }
    /** Раскладывает массив данных по кластерам, заданным списком экстентов. */
    private void writeNonResident(List<Extent> xs, byte[] data) {
        int pos = 0, size = state.getSuperblock().clusterSize();
        for (Extent x : xs)
            for (int c = x.startCluster(); c < x.endExclusive(); c++) {
                byte[] b = new byte[size];
                int n = Math.min(size, data.length - pos);
                if (n > 0)
                    System.arraycopy(data, pos, b, 0, n);
                pos += n;
                state.getClusterData().put(c, b);
            }
    }
    /** Инициализирует одну из фиксированных системных записей MFT 0–5. */
    private MftEntry systemEntry(int record, String name, boolean dir) {
        MftEntry e = state.entry(record);
        e.setUsed(true);
        e.setSystem(true);
        e.setDirectory(dir);
        e.setName(name);
        e.setParentRecord(ROOT_RECORD);
        e.setOwnerUid(0);
        e.setGroupGid(0);
        e.setPermissions(0600);
        if (dir)
            e.setDirectoryIndex(directoryTree());
        return e;
    }
    /**
     * Создаёт B-дерево каталога с порядком t из суперблока и сравнением без
     * регистра.
     */
    private BTree<String, Integer> directoryTree() {
        return new BTree<>(state.getSuperblock().bTreeDegree(),
                String.CASE_INSENSITIVE_ORDER);
    }
    /**
     * Вычисляет размер кластера по формуле 1.1: от 512 байт до 64 КиБ.
     */
    private static int clusterSize(long bytes, int sector) {
        // Формула 1.1: C = S * 2^k, где число кластеров не превышает 2^20.
        long max = 1L << 20;
        int k = 0;
        while (k < 7 && bytes / (sector * (1L << k)) > max)
            k++;
        return sector * (1 << k);
    }
    /** Выполняет целочисленное деление с округлением вверх. */
    private static int ceil(long a, long b) { return (int)((a + b - 1) / b); }

    /**
     * Проверяет ограничение из проекта: имя 1–64 символа и без разделителя пути.
     */
    private void validateName(String s) {
        if (s == null || s.isBlank() || s.length() > 64 || s.contains("/"))
            throw new IllegalArgumentException("Имя: от 1 до 64 символов, без '/'");
    }
    /** Проверяет, что системный вызов выполняется над смонтированным образом. */
    private void ensureMounted() {
        if (state == null)
            throw new IllegalStateException("Сначала выполните format или mount");
    }
    /**
     * Проверяет наличие смонтированного тома и аутентифицированного
     * пользователя.
     */
    private void requireSession() {
        ensureMounted();
        if (session == null)
            throw new SecurityException("Сначала выполните login");
    }
    /** Проверяет, что текущий пользователь имеет роль ADMIN. */
    private void admin() {
        requireSession();
        if (session.role() != Role.ADMIN)
            throw new SecurityException("Нужны права администратора");
    }
    /** Сохраняет образ после успешно изменяющего том системного вызова. */
    private void changed() {
        try {
            save();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить образ", e);
        }
    }
    /** Передаёт текущее состояние в хранилище образов. */
    private void save() throws IOException { store.save(imagePath, state); }

    /**
     * Результат разделения пути: каталог-родитель и имя создаваемого/ищемого
     * объекта.
     */
    private record Parent(int parent, String name) {}
}
