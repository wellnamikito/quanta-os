package org.quantaos.process;

/**
 * PCB — дескриптор процесса, который хранит планировщик.
 *
 * <p>Абсолютный приоритет находится в диапазоне 0..15: 0 — самый высокий,
 * 15 — самый низкий. Относительный приоритет (nice) — поправка пользователя
 * от -2 до 2. Динамический приоритет также лежит в диапазоне 0..15 и во
 * время работы сопоставляется одной из трёх очередей MLFQ.</p>
 */
public final class ProcessControlBlock {
    private final int pid;
    private final int parentPid;
    private final String name;
    private final int absolutePriority;
    private int relativePriority;
    private int dynamicPriority;
    private ProcessState state;
    private long executedTicks;
    private long waitedTicks;

    /** Создаёт новый процесс, готовый к первому запуску. */
    ProcessControlBlock(int pid, int parentPid, String name, int absolutePriority,
                        int relativePriority, int dynamicPriority) {
        this.pid = pid;
        this.parentPid = parentPid;
        this.name = name;
        this.absolutePriority = absolutePriority;
        this.relativePriority = relativePriority;
        this.dynamicPriority = dynamicPriority;
        this.state = ProcessState.READY;
    }

    /** @return уникальный номер процесса. */
    public int pid() { return pid; }
    /** @return PID процесса, создавшего данный процесс; у init это 0. */
    public int parentPid() { return parentPid; }
    /** @return имя, используемое в таблице процессов. */
    public String name() { return name; }
    /** @return постоянный абсолютный приоритет от 0 до 15. */
    public int absolutePriority() { return absolutePriority; }
    /** @return пользовательская nice-поправка от -2 до 2. */
    public int relativePriority() { return relativePriority; }
    /** @return текущий динамический приоритет от 0 до 15. */
    public int dynamicPriority() { return dynamicPriority; }
    /** @return текущее состояние процесса. */
    public ProcessState state() { return state; }
    /** @return число выполненных процессорных тактов. */
    public long executedTicks() { return executedTicks; }
    /** @return число тактов ожидания в очереди готовности. */
    public long waitedTicks() { return waitedTicks; }

    /** Учитывает один такт работы процесса на процессоре. */
    void ranOneTick() { executedTicks++; }
    /** Учитывает один такт ожидания в очереди готовности. */
    void waitedOneTick() { waitedTicks++; }
    /** Сбрасывает ожидание после повышения или запуска процесса. */
    void clearWaiting() { waitedTicks = 0; }
    /** Изменяет состояние процесса только средствами планировщика. */
    void setState(ProcessState state) { this.state = state; }
    /** Изменяет динамический приоритет, из которого выводится очередь MLFQ. */
    void setDynamicPriority(int dynamicPriority) {
        this.dynamicPriority = dynamicPriority;
    }
    /** Изменяет относительную поправку к абсолютному приоритету. */
    void setRelativePriority(int relativePriority) {
        this.relativePriority = relativePriority;
    }
}
