package org.quantaos.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Учебный MLFQ-планировщик с Round Robin внутри каждого уровня.
 *
 * <p>0 — самый высокий приоритет, 15 — самый низкий. Отрицательное значение
 * nice повышает приоритет. Полный квант понижает процесс, а ожидание и
 * завершение I/O повышают его.</p>
 */
public final class MultilevelFeedbackScheduler {
    private static final int[] QUEUE_MIN_PRIORITY = {0, 6, 11};
    private static final int[] QUEUE_MAX_PRIORITY = {5, 10, 15};
    private static final int AGING_TICKS = 6;

    private final List<ArrayDeque<ProcessControlBlock>> queues = List.of(
            new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    private final Map<Integer, ProcessControlBlock> processes = new HashMap<>();
    /** Настраиваемые кванты для очередей 0, 1 и 2. */
    private final int[] quanta = {2, 4, 8};
    private int nextPid = 1;
    private ProcessControlBlock current;
    private int remainingQuantum;

    /** Создаёт процесс без указанного родителя; удобно для автономных тестов. */
    public int create(String name, int absolutePriority, int relativePriority) {
        return create(name, 0, absolutePriority, relativePriority);
    }

    /** Создаёт процесс и ставит его в MLFQ-очередь. */
    public int create(String name, int parentPid, int absolutePriority,
                      int relativePriority) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Имя процесса не должно быть пустым");
        if (parentPid < 0)
            throw new IllegalArgumentException("PPID не может быть отрицательным");
        validateAbsolute(absolutePriority);
        validateRelative(relativePriority);
        ProcessControlBlock process = new ProcessControlBlock(nextPid++, parentPid, name,
                absolutePriority, relativePriority,
                effectivePriority(absolutePriority, relativePriority));
        processes.put(process.pid(), process);
        enqueue(process);
        return process.pid();
    }

    /** Выполняет один условный такт CPU. */
    public String tick() {
        List<String> events = new ArrayList<>();
        if (current == null)
            dispatch(events);
        if (current == null) {
            ageReadyProcesses(events);
            return "idle: готовых процессов нет";
        }

        ProcessControlBlock running = current;
        int previousQueue = queueOf(running.dynamicPriority());
        running.ranOneTick();
        remainingQuantum--;
        events.add("PID " + running.pid() + " выполняется, остаток кванта="
                + remainingQuantum);
        ageReadyProcesses(events);
        if (remainingQuantum == 0) {
            int previousPriority = running.dynamicPriority();
            int nextQueue = Math.min(previousQueue + 1, queues.size() - 1);
            /*
             * Процесс с очереди 2 уже нельзя понизить уровнем ниже. Его числовой
             * приоритет всё равно может ухудшаться до 15, но не должен внезапно
             * улучшаться до 11 после каждого кванта.
             */
            int nextPriority = previousQueue == nextQueue
                    ? Math.min(QUEUE_MAX_PRIORITY[nextQueue], previousPriority + 1)
                    : QUEUE_MIN_PRIORITY[nextQueue];
            running.setDynamicPriority(nextPriority);
            running.setState(ProcessState.READY);
            running.clearWaiting();
            enqueue(running);
            current = null;
            events.add("PID " + running.pid() + " исчерпал квант: приоритет "
                    + previousPriority + " -> " + running.dynamicPriority()
                    + ", очередь " + previousQueue + " -> " + nextQueue);
        }
        return String.join("; ", events);
    }

    /** Выполняет несколько тактов. */
    public List<String> tick(int count) {
        if (count < 1 || count > 10_000)
            throw new IllegalArgumentException("Число тактов должно быть от 1 до 10000");
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++)
            events.add("t=" + (i + 1) + ": " + tick());
        return events;
    }

    /** Блокирует процесс, например при ожидании I/O или пустого канала. */
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

    /** Возвращает процесс из I/O-ожидания с интерактивным повышением. */
    public void wake(int pid) {
        ProcessControlBlock process = process(pid);
        if (process.state() != ProcessState.BLOCKED)
            throw new IllegalArgumentException("Разбудить можно только заблокированный процесс");
        int oldQueue = queueOf(process.dynamicPriority());
        int promotedQueue = Math.max(0, oldQueue - 1);
        // Повышаем ровно на один диапазон, а не прыгаем сразу к его максимуму.
        process.setDynamicPriority(QUEUE_MAX_PRIORITY[promotedQueue]);
        process.clearWaiting();
        enqueue(process);
    }

    /** Завершает процесс. */
    public void terminate(int pid) {
        ProcessControlBlock process = process(pid);
        removeFromReadyQueues(process);
        if (current == process) {
            current = null;
            remainingQuantum = 0;
        }
        process.setState(ProcessState.TERMINATED);
    }

    /** Меняет nice-поправку и пересчитывает динамический приоритет. */
    public void setRelativePriority(int pid, int relativePriority) {
        validateRelative(relativePriority);
        ProcessControlBlock process = process(pid);
        if (process.state() == ProcessState.TERMINATED)
            throw new IllegalArgumentException("Нельзя менять приоритет завершённого процесса");
        process.setRelativePriority(relativePriority);
        process.setDynamicPriority(effectivePriority(process.absolutePriority(), relativePriority));
        process.clearWaiting();
        if (process.state() == ProcessState.READY) {
            removeFromReadyQueues(process);
            enqueue(process);
        }
    }

    /** Меняет длину кванта указанной очереди. */
    public void setQuantum(int queueLevel, int ticks) {
        validateQueue(queueLevel);
        if (ticks < 1 || ticks > 1_000)
            throw new IllegalArgumentException("Квант должен быть от 1 до 1000 тактов");
        quanta[queueLevel] = ticks;
    }

    /** @return копия квантов очередей 0, 1 и 2. */
    public int[] quanta() { return quanta.clone(); }

    /** Возвращает строки для ps, упорядоченные по PID. */
    public List<ProcessSnapshot> snapshot() {
        return processes.values().stream().sorted(Comparator.comparingInt(
                ProcessControlBlock::pid)).map(p -> new ProcessSnapshot(p.pid(),
                p.parentPid(), p.name(), p.state(), p.absolutePriority(),
                p.relativePriority(), p.dynamicPriority(), queueOf(p.dynamicPriority()),
                p.executedTicks(), p.waitedTicks(), p == current ? remainingQuantum : 0)).toList();
    }

    /** @return текущий процесс и остаток его кванта или {@code idle}. */
    public String currentProcess() {
        return current == null ? "idle" : "PID " + current.pid() + " (" + current.name()
                + "), priority=" + current.dynamicPriority() + ", queue="
                + queueOf(current.dynamicPriority()) + ", quantum=" + remainingQuantum;
    }

    private void dispatch(List<String> events) {
        for (int level = 0; level < queues.size(); level++) {
            ProcessControlBlock candidate = queues.get(level).pollFirst();
            if (candidate == null)
                continue;
            current = candidate;
            current.setState(ProcessState.RUNNING);
            current.clearWaiting();
            remainingQuantum = quanta[level];
            events.add("диспетчер выбрал PID " + current.pid() + " из очереди " + level
                    + ", квант=" + remainingQuantum);
            return;
        }
    }

    private void ageReadyProcesses(List<String> events) {
        List<ProcessControlBlock> promote = new ArrayList<>();
        for (ArrayDeque<ProcessControlBlock> queue : queues) {
            Iterator<ProcessControlBlock> iterator = queue.iterator();
            while (iterator.hasNext()) {
                ProcessControlBlock process = iterator.next();
                process.waitedOneTick();
                int oldQueue = queueOf(process.dynamicPriority());
                if (process.waitedTicks() >= AGING_TICKS && oldQueue > 0) {
                    iterator.remove();
                    int newQueue = oldQueue - 1;
                    process.setDynamicPriority(QUEUE_MAX_PRIORITY[newQueue]);
                    process.clearWaiting();
                    promote.add(process);
                    events.add("aging повысил PID " + process.pid() + " до очереди "
                            + newQueue);
                }
            }
        }
        promote.forEach(this::enqueue);
    }

    private void enqueue(ProcessControlBlock process) {
        process.setState(ProcessState.READY);
        queues.get(queueOf(process.dynamicPriority())).addLast(process);
    }

    private void removeFromReadyQueues(ProcessControlBlock process) {
        queues.forEach(queue -> queue.remove(process));
    }

    private ProcessControlBlock process(int pid) {
        ProcessControlBlock process = processes.get(pid);
        if (process == null)
            throw new IllegalArgumentException("Нет процесса с PID " + pid);
        return process;
    }

    private int effectivePriority(int absolute, int relative) {
        return Math.max(0, Math.min(15, absolute + relative));
    }

    private int queueOf(int priority) {
        for (int queue = 0; queue < QUEUE_MAX_PRIORITY.length; queue++) {
            if (priority <= QUEUE_MAX_PRIORITY[queue])
                return queue;
        }
        return QUEUE_MAX_PRIORITY.length - 1;
    }

    private void validateAbsolute(int priority) {
        if (priority < 0 || priority > 15)
            throw new IllegalArgumentException("Абсолютный приоритет: от 0 до 15");
    }

    private void validateRelative(int priority) {
        if (priority < -2 || priority > 2)
            throw new IllegalArgumentException("Относительный приоритет: от -2 до 2");
    }

    private void validateQueue(int queue) {
        if (queue < 0 || queue >= queues.size())
            throw new IllegalArgumentException("Номер очереди: 0, 1 или 2");
    }
}
