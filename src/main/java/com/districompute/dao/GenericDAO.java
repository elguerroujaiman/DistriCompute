package com.districompute.dao;

import java.util.List;
import java.util.Optional;

/**
 * Generic DAO (Data Access Object) interface.
 * Provides CRUD operations for any entity type.
 *
 * @param <T>  the entity type
 * @param <ID> the identifier type
 */
public interface GenericDAO<T, ID> {
    void save(T entity);
    void update(T entity);
    void delete(ID id);
    Optional<T> findById(ID id);
    List<T> findAll();
}
