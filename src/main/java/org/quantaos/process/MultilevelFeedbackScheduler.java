package org.quantaos.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Планировщик MLFQ: многоуровневые очереди с обратной связью.
 *
 * <p>Очередь 0 имеет наивысший динамический приоритет и короткий квант,
 * очередь 2 — наименьший приоритет и длинный квант. Процесс, исчерпавший
 * квант, понижается. Долго ожидающий процесс повышается механизмом aging —
 * это не даёт низкоприоритетным задачам голодать.</p>
 */
public final class MultilevelFeedbackScheduler {
    /** Кванты времени для трёх уровней, в условных тактах. */
    private static final int[] QUANTA = {2, 4, 8};
    /** После стольких тактов ожидания процесс повышается на один уровень. */
    private static final int AGING_TICKS = 6;

    private final List<ArrayDeque<ProcessControlBlock>> queues = List.of(
            new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    private final Map<Integer, ProcessControlBlock> processes = new HashMap<>();
    private int nextPid = 1;
    private ProcessControlBlock current;
    private int remainingQuantum;

    /**
     * Создаёт процесс и помещает его в очередь, вычисленную из абсолютного и
     * относительного приоритетов.
     */
    public int create(String name, int absolutePriority, int relativePriority) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Имя процесса не должно быть пустым");
        validateAbsolute(absolutePriority);
        validateRelative(relativePriority);
        int dynamic = baseQueue(absolutePriority, relativePriority);
        ProcessControlBlock process = new ProcessControlBlock(nextPid++, name,
                absolutePriority, relativePriority, dynamic);
        processes.put(process.pid(), process);
        enqueue(process);
        return process.pid();
    }

    /**
     * Выполняет один такт. При необходимости выбирает самый приоритетный готовый
     * процесс и после исчерпания кванта переводит его в более низкую очередь.
     *
     * @return понятное событие для демонстрации работы алгоритма
     */
    public String tick() {
        List<String> events = new ArrayList<>();
        if (current == null)
            dispatch(events);
        if (current == null) {
            ageReadyProcesses(events);
            return "idle: готовых процессов нет";
        }
        ProcessControlBlock running = current;
        running.ranOneTick();
        remainingQuantum--;
        events.add("PID " + running.pid() + " выполняется, остаток кванта="
                + remainingQuantum);
        ageReadyProcesses(events);
        if (remainingQuantum == 0) {
            int previous = running.dynamicPriority();
            running.setDynamicPriority(Math.min(previous + 1, QUANTA.length - 1));
            running.setState(ProcessState.READY);
            running.clearWaiting();
            enqueue(running);
            current = null;
            events.add("PID " + running.pid() + " исчерпал квант: очередь "
                    + previous + " -> " + running.dynamicPriority());
        }
        return String.join("; ", events);
    }

    /** Выполняет несколько тактов и возвращает события каждого такта. */
    public List<String> tick(int count) {
        if (count < 1 || count > 10_000)
            throw new IllegalArgumentException("Число тактов должно быть от 1 до 10000");
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++)
            events.add("t=" + (i + 1) + ": " + tick());
        return events;
    }

    /** Переводит готовый или выполняемый процесс в состояние ожидания. */
    public void block(int pid) {
        ProcessControlBlock process = process(pid);
        if (process.state() == ProcessState.TERMINATED)
            throw new IllegalArgumentException("Завершённый процесс нельзя блокировать");
        if (process.state() == ProcessState.BLOCKED)
            throw new IllegalArgumentException("Процесс уже заблокирован");
        removeFromReadyQueues(process);
        if (current == process) {
            current = null;
            remainingQuantum = 0;
        }
        process.setState(ProcessState.BLOCKED);
    }

    /**
     * Возвращает разблокированный процесс в готовые. Он получает небольшое
     * интерактивное повышение, что и является обратной связью MLFQ.
     */
    public void wake(int pid) {
        ProcessControlBlock process = process(pid);
        if (process.state() != ProcessState.BLOCKED)
            throw new IllegalArgumentException("Разбудить можно только заблокированный процесс");
        process.setDynamicPriority(Math.max(0, process.dynamicPriority() - 1));
        process.clearWaiting();
        process.setState(ProcessState.READY);
        enqueue(process);
    }

    /** Завершает процесс и удаляет его из очередей или с процессора. */
    public void terminate(int pid) {
        ProcessControlBlock process = process(pid);
        removeFromReadyQueues(process);
        if (current == process) {
            current = null;
            remainingQuantum = 0;
        }
        process.setState(ProcessState.TERMINATED);
    }

    /** Меняет относительный приоритет и пересчитывает начальный уровень MLFQ. */
    public void setRelativePriority(int pid, int relativePriority) {
        validateRelative(relativePriority);
        ProcessControlBlock process = process(pid);
        if (process.state() == ProcessState.TERMINATED)
            throw new IllegalArgumentException("Нельзя менять приоритет завершённого процесса");
        process.setRelativePriority(relativePriority);
        process.setDynamicPriority(baseQueue(process.absolutePriority(), relativePriority));
        process.clearWaiting();
        if (process.state() == ProcessState.READY) {
            removeFromReadyQueues(process);
            enqueue(process);
        }
    }

    /** Возвращает снимок процессов, упорядоченный по PID. */
    public List<ProcessSnapshot> snapshot() {
        return processes.values().stream().sorted(Comparator.comparingInt(
                ProcessControlBlock::pid)).map(p -> new ProcessSnapshot(p.pid(),
                p.name(), p.state(), p.absolutePriority(), p.relativePriority(),
                p.dynamicPriority(), p.executedTicks(), p.waitedTicks())).toList();
    }

    /** @return описание текущего процесса или {@code idle}. */
    public String currentProcess() {
        return current == null ? "idle" : "PID " + current.pid() + " (" + current.name()
                + "), queue=" + current.dynamicPriority() + ", quantum=" + remainingQuantum;
    }

    /** Выбирает первый процесс из самой приоритетной непустой очереди. */
    private void dispatch(List<String> events) {
        for (int level = 0; level < queues.size(); level++) {
            ProcessControlBlock candidate = queues.get(level).pollFirst();
            if (candidate == null)
                continue;
            current = candidate;
            current.setState(ProcessState.RUNNING);
            current.clearWaiting();
            remainingQuantum = QUANTA[level];
            events.add("диспетчер выбрал PID " + current.pid() + " из очереди " + level
                    + ", квант=" + remainingQuantum);
            return;
        }
    }

    /** Повышает долго ждущие процессы, предотвращая голодание очереди 2. */
    private void ageReadyProcesses(List<String> events) {
        List<ProcessControlBlock> promote = new ArrayList<>();
        for (ArrayDeque<ProcessControlBlock> queue : queues) {
            Iterator<ProcessControlBlock> iterator = queue.iterator();
            while (iterator.hasNext()) {
                ProcessControlBlock process = iterator.next();
                process.waitedOneTick();
                if (process.waitedTicks() >= AGING_TICKS && process.dynamicPriority() > 0) {
                    iterator.remove();
                    process.setDynamicPriority(process.dynamicPriority() - 1);
                    process.clearWaiting();
                    promote.add(process);
                    events.add("aging повысил PID " + process.pid() + " до очереди "
                            + process.dynamicPriority());
                }
            }
        }
        promote.forEach(this::enqueue);
    }

    /** Добавляет процесс в хвост его текущей очереди готовности. */
    private void enqueue(ProcessControlBlock process) {
        process.setState(ProcessState.READY);
        queues.get(process.dynamicPriority()).addLast(process);
    }

    /** Удаляет процесс из всех очередей готовности, если он там есть. */
    private void removeFromReadyQueues(ProcessControlBlock process) {
        queues.forEach(queue -> queue.remove(process));
    }

    /** Находит PCB или сообщает об отсутствующем PID. */
    private ProcessControlBlock process(int pid) {
        ProcessControlBlock process = processes.get(pid);
        if (process == null)
            throw new IllegalArgumentException("Нет процесса с PID " + pid);
        return process;
    }

    /** Суммирует абсолютный класс и относительную поправку, ограничивая диапазон. */
    private int baseQueue(int absolute, int relative) {
        return Math.max(0, Math.min(QUANTA.length - 1, absolute + relative));
    }

    /** Проверяет допустимый абсолютный класс из трёх очередей. */
    private void validateAbsolute(int priority) {
        if (priority < 0 || priority >= QUANTA.length)
            throw new IllegalArgumentException("Абсолютный приоритет: 0, 1 или 2");
    }

    /** Проверяет границы пользовательской относительной поправки. */
    private void validateRelative(int priority) {
        if (priority < -2 || priority > 2)
            throw new IllegalArgumentException("Относительный приоритет: от -2 до 2");
    }
}

