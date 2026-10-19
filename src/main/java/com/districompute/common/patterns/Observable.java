package com.districompute.common.patterns;

/**
 * Observable (Subject) interface for the Observer design pattern.
 *
 * @param <T> The type of event data being published.
 */
public interface Observable<T> {
    void addObserver(Observer<T> observer);
    void removeObserver(Observer<T> observer);
    void notifyObservers(T event);
}
