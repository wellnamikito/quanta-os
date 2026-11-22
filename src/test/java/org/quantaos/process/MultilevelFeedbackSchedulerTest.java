package org.quantaos.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Проверяет основные переходы многоуровневого планировщика MLFQ. */
class MultilevelFeedbackSchedulerTest {
    /** Высокий абсолютный приоритет выбирается раньше низкого и понижается по кванту. */
    @Test
    void choosesHighPriorityFirstAndDemotesAfterQuantum() {
        MultilevelFeedbackScheduler scheduler = new MultilevelFeedbackScheduler();
        int low = scheduler.create("low", 2, 0);
        int high = scheduler.create("high", 0, 0);
        scheduler.tick(2);
        ProcessSnapshot highSnapshot = scheduler.snapshot().stream()
                .filter(p -> p.pid() == high).findFirst().orElseThrow();
        ProcessSnapshot lowSnapshot = scheduler.snapshot().stream()
                .filter(p -> p.pid() == low).findFirst().orElseThrow();
        assertEquals(1, highSnapshot.dynamicPriority());
        assertEquals(2, highSnapshot.executedTicks());
        assertEquals(0, lowSnapshot.executedTicks());
    }
    /** Разблокированный процесс получает интерактивное повышение приоритета. */
    @Test
    void wakingProcessPromotesItsDynamicPriority() {
        MultilevelFeedbackScheduler scheduler = new MultilevelFeedbackScheduler();
        int pid = scheduler.create("io", 2, 0);
        scheduler.block(pid);
        scheduler.wake(pid);
        ProcessSnapshot process = scheduler.snapshot().getFirst();
        assertEquals(ProcessState.READY, process.state());
        assertEquals(1, process.dynamicPriority());
        assertTrue(scheduler.tick().contains("PID " + pid));
    }
}

