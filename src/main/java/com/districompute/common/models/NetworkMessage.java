package com.districompute.common.models;

import java.io.Serializable;

/**
 * Represents a message exchanged between client and server over TCP sockets.
 * Uses a type + payload approach for flexible communication.
 */
public class NetworkMessage implements Serializable {

    private static final long serialVersionUID = 2L;

    public enum Type {
        // Auth
        LOGIN_REQUEST,
        LOGIN_RESPONSE,
        REGISTER_REQUEST,
        REGISTER_RESPONSE,
        LOGOUT,

        // Task operations
        SUBMIT_TASK,
        SUBMIT_TASK_RESPONSE,
        SUBMIT_BATCH,
        SUBMIT_BATCH_RESPONSE,
        CANCEL_TASK,
        CANCEL_TASK_RESPONSE,
        EDIT_TASK,
        EDIT_TASK_RESPONSE,
        DELETE_TASK,
        DELETE_TASK_RESPONSE,
        GET_MY_TASKS,
        GET_MY_TASKS_RESPONSE,
        GET_ALL_TASKS,
        GET_ALL_TASKS_RESPONSE,
        GET_QUEUE,
        GET_QUEUE_RESPONSE,

        // Real-time updates (server → client push)
        TASK_STATUS_UPDATE,
        TASK_PROGRESS_UPDATE,
        NOTIFICATION,

        // Admin
        GET_ALL_USERS,
        GET_ALL_USERS_RESPONSE,
        DELETE_USER,
        DELETE_USER_RESPONSE,
        GET_LOGS,
        GET_LOGS_RESPONSE,

        // User list (for assignment dropdown — available to all authenticated users)
        GET_USER_LIST,
        GET_USER_LIST_RESPONSE,

        // Stats
        GET_STATS,
        GET_STATS_RESPONSE,

        // Slave management (Master ↔ Slave)
        SLAVE_REGISTER,
        SLAVE_REGISTER_ACK,
        SLAVE_HEARTBEAT,
        SLAVE_HEARTBEAT_ACK,
        SLAVE_EXECUTE_TASK,
        SLAVE_TASK_RESULT,
        SLAVE_LOG,
        SLAVE_CANCEL_TASK,

        // Slave info (Client ↔ Master)
        GET_SLAVES,
        GET_SLAVES_RESPONSE,
        GET_SLAVE_LOGS,
        GET_SLAVE_LOGS_RESPONSE,

        // System
        PING,
        PONG,
        ERROR
    }

    private Type type;
    private Object payload;
    private boolean success;
    private String message;
    private String sessionToken;

    public NetworkMessage() {}

    public NetworkMessage(Type type, Object payload) {
        this.type = type;
        this.payload = payload;
        this.success = true;
    }

    public static NetworkMessage success(Type type, Object payload) {
        NetworkMessage msg = new NetworkMessage(type, payload);
        msg.success = true;
        return msg;
    }

    public static NetworkMessage error(Type type, String errorMessage) {
        NetworkMessage msg = new NetworkMessage();
        msg.type = type;
        msg.success = false;
        msg.message = errorMessage;
        return msg;
    }

    public static NetworkMessage of(Type type) {
        return new NetworkMessage(type, null);
    }

    // Getters & Setters
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }

    @Override
    public String toString() {
        return "NetworkMessage{type=" + type + ", success=" + success + ", message='" + message + "'}";
    }
}
