package org.quantaos.process;

/**
 * PCB - дескриптор процесса, который хранит планировщик.
 *
 * <p>Абсолютный приоритет задаёт исходный класс процесса: 0 - высокий,
 * 1 - обычный, 2 - низкий. Относительный приоритет - поправка пользователя.
 * Динамический приоритет - номер очереди, который меняется самим
 * алгоритмом многоуровневой очереди с обратными связями</p>
 */
public final class ProcessControlBlock {
    private final int pid;
    private final String name;
    private final int absolutePriority;
    private int relativePriority;
    private int dynamicPriority;
    private ProcessState state;
    private long executedTicks;
    private long waitedTicks;

    /** Создает новый процесс, готовый к первому запуску. */
    public ProcessControlBlock(int pid, String name, int absolutePriority,
                               int relativePriority, int dynamicPriority) {
        this.pid = pid;
        this.name = name;
        this.absolutePriority = absolutePriority;
        this.relativePriority = relativePriority;
        this.dynamicPriority = dynamicPriority;
        this.state = ProcessState.READY;
    }

    /** return уникальный номер процесса. */
    public int pid() {return pid; }

    /** return имя, используемое в таблице процессов. */
    public String name() { return name; }

    /** return постоянный исходный класс приоритета. */
    public int absolutePriority(){ return absolutePriority;}

    /** return пользовательская поправка к исходному классу. */
    public int relativePriority() { return relativePriority; }

    /** return текущий номер очереди, выбранный алгоритмом
     *  многоуровневой очереди с обратными связями. */
    public int dynamicPriority() { return dynamicPriority; }

    /** return текущее состояние процесса. */
    public ProcessState state() { return state; }

    /** return число выполненных процессорных тактов. */
    public long executedTicks() { return executedTicks; }

    /** return число тактов ожидания в очереди готовности.. */
    public long waitedTicks() { return waitedTicks; }

    /** Учитывает один такт работы процесса на процессоре.*/
    void ranOneTick() { executedTicks++; }

    /** Учитывает один такт ожидания очереди готовности. */
    void waitedOneTick() { waitedTicks++; }

    /** Сбрасывает ожидание после повышения или запуска процесса. */
    void clearWaiting() { waitedTicks = 0; }

    /** Изменяет состояние процесса только средствами планировщика. */
    void setState(ProcessState state) { this.state = state; }

    /** Изменяет очередь динамического приоритета. */
    void setDynamicPriority(int dynamicPriority) {
        this.dynamicPriority = dynamicPriority;
    }

    /** Изменяет относительную поправку к абсолютному приоритету */
    void setRelativePriority(int relativePriority) {
        this.relativePriority = relativePriority;
    }
}
