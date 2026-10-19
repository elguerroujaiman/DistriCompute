package com.districompute.dao;

import com.districompute.common.models.Task;
import com.districompute.common.models.Task.Priority;
import com.districompute.common.models.Task.Status;

import java.util.List;

/**
 * DAO interface for Task persistence operations.
 */
public interface TaskDAO extends GenericDAO<Task, String> {
    List<Task> findByUserId(String userId);
    List<Task> findByStatus(Status status);
    List<Task> findByPriority(Priority priority);
    List<Task> findByBatchId(String batchId);
    List<Task> findByAssignedUserId(String userId);
    List<Task> findPending();
}
