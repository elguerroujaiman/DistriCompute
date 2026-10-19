package com.districompute.common.patterns;

/**
 * Observer interface for the Observer design pattern.
 * Used for real-time task status updates pushed from server to clients.
 *
 * @param <T> The type of event data being observed.
 */
public interface Observer<T> {
    /**
     * Called when the observed subject has a new event.
     *
     * @param event the event data
     */
    void onEvent(T event);
}
