package com.districompute.common.patterns;

import com.districompute.common.models.Task;

/**
 * Strategy interface for task execution.
 *
 * In V1: LocalExecutionStrategy — the Master executes the task directly.
 * In V2: DistributedExecutionStrategy — the Master distributes to Slaves.
 *
 * This design allows swapping execution strategies without modifying
 * the TaskExecutor or any other component (Open/Closed Principle).
 */
public interface ExecutionStrategy {
    /**
     * Execute the given task and populate its result fields.
     *
     * @param task the task to execute
     */
    void execute(Task task);

    /**
     * Returns a human-readable name for this strategy.
     */
    String getStrategyName();
}
