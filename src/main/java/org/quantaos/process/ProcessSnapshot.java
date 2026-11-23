package org.quantaos.process;

/** Неизменяемая строка таблицы процессов для команды {@code ps}. */
public record ProcessSnapshot(
        int pid,
        int parentPid,
        String name,
        ProcessState state,
        int absolutePriority,
        int relativePriority,
        int dynamicPriority,
        int queueLevel,
        long executedTicks,
        long waitedTicks,
        int quantumLeft
) {}
