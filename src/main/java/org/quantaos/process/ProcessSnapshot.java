package org.quantaos.process;

/** Неизменяемая строка таблицы процессов для команды {@code ps}. */
public record ProcessSnapshot(
        int pid,
        String name,
        ProcessState state,
        int absolutePriority,
        int relativePriority,
        int dynamicPriority,
        long executedTicks,
        long waitedTicks
) {
}
