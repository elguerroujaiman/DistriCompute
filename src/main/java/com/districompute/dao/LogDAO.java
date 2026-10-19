package com.districompute.dao;

import com.districompute.common.models.LogEntry;
import java.util.List;

/**
 * DAO interface for LogEntry persistence operations.
 */
public interface LogDAO extends GenericDAO<LogEntry, String> {
    List<LogEntry> findByUserId(String userId);
    List<LogEntry> findRecent(int limit);
}
