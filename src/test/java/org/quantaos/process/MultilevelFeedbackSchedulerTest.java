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
        int low = scheduler.create("low", 15, 0);
        int high = scheduler.create("high", 0, 0);
        scheduler.tick(2);
        ProcessSnapshot highSnapshot = scheduler.snapshot().stream()
                .filter(p -> p.pid() == high).findFirst().orElseThrow();
        ProcessSnapshot lowSnapshot = scheduler.snapshot().stream()
                .filter(p -> p.pid() == low).findFirst().orElseThrow();
        assertEquals(6, highSnapshot.dynamicPriority());
        assertEquals(1, highSnapshot.queueLevel());
        assertEquals(2, highSnapshot.executedTicks());
        assertEquals(0, lowSnapshot.executedTicks());
    }
    /** Разблокированный процесс получает интерактивное повышение приоритета. */
    @Test
    void wakingProcessPromotesItsDynamicPriority() {
        MultilevelFeedbackScheduler scheduler = new MultilevelFeedbackScheduler();
        int pid = scheduler.create("io", 15, 0);
        scheduler.block(pid);
        scheduler.wake(pid);
        ProcessSnapshot process = scheduler.snapshot().getFirst();
        assertEquals(ProcessState.READY, process.state());
        assertEquals(10, process.dynamicPriority());
        assertEquals(1, process.queueLevel());
        assertTrue(scheduler.tick().contains("PID " + pid));
    }

    /** Низкоприоритетная задача на очереди 2 не улучшается после своего кванта. */
    @Test
    void doesNotImproveLowestPriorityProcessAfterItsQuantum() {
        MultilevelFeedbackScheduler scheduler = new MultilevelFeedbackScheduler();
        int pid = scheduler.create("batch", 15, 0);
        scheduler.tick(8);
        ProcessSnapshot process = scheduler.snapshot().getFirst();
        assertEquals(pid, process.pid());
        assertEquals(15, process.dynamicPriority());
        assertEquals(2, process.queueLevel());
    }

    /** PPID, nice и настраиваемая длина кванта отображаются в снимке процесса. */
    @Test
    void exposesParentNiceAndConfigurableQuantum() {
        MultilevelFeedbackScheduler scheduler = new MultilevelFeedbackScheduler();
        int pid = scheduler.create("editor", 2, 6, -2);
        ProcessSnapshot process = scheduler.snapshot().getFirst();
        assertEquals(2, process.parentPid());
        assertEquals(6, process.absolutePriority());
        assertEquals(-2, process.relativePriority());
        assertEquals(4, process.dynamicPriority());
        assertEquals(0, process.queueLevel());

        scheduler.setQuantum(0, 3);
        scheduler.tick();
        assertEquals(2, scheduler.snapshot().getFirst().quantumLeft());
    }
}
