package com.districompute.dao;

import com.districompute.common.models.User;
import java.util.Optional;

/**
 * DAO interface for User persistence operations.
 */
public interface UserDAO extends GenericDAO<User, String> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
