package com.districompute.common.utils;

import java.util.prefs.Preferences;

/**
 * Manages connection configuration for the client.
 * Supports localhost (default) and remote (ngrok) connection modes.
 *
 * Persists the user's choice using Java Preferences API.
 */
public class ConnectionConfig {

    public enum Mode { LOCAL, LAN, REMOTE }

    private static final String PREF_MODE = "connection_mode";
    private static final String PREF_REMOTE_HOST = "remote_host";
    private static final String PREF_REMOTE_PORT = "remote_port";
    private static final String PREF_LAN_HOST = "lan_host";
    private static final Preferences prefs = Preferences.userNodeForPackage(ConnectionConfig.class);

    private static volatile ConnectionConfig instance;

    private Mode mode;
    private String remoteHost;
    private int remotePort;
    private String lanHost;

    private ConnectionConfig() {
        this.mode = loadMode();
        this.remoteHost = prefs.get(PREF_REMOTE_HOST, "");
        this.remotePort = prefs.getInt(PREF_REMOTE_PORT, 9090);
        this.lanHost = prefs.get(PREF_LAN_HOST, "");
    }

    public static ConnectionConfig getInstance() {
        if (instance == null) {
            synchronized (ConnectionConfig.class) {
                if (instance == null) instance = new ConnectionConfig();
            }
        }
        return instance;
    }

    private Mode loadMode() {
        String saved = prefs.get(PREF_MODE, null);
        if (saved != null) {
            try { return Mode.valueOf(saved); } catch (IllegalArgumentException ignored) {}
        }
        return Mode.LOCAL;
    }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) {
        this.mode = mode;
        prefs.put(PREF_MODE, mode.name());
    }

    public String getRemoteHost() { return remoteHost; }

    public String getLanHost() { return lanHost; }

    public void setLanHost(String host) {
        this.lanHost = host;
        prefs.put(PREF_LAN_HOST, host);
    }

    public void setRemoteHost(String host) {
        this.remoteHost = host;
        prefs.put(PREF_REMOTE_HOST, host);
    }

    public int getRemotePort() { return remotePort; }

    public void setRemotePort(int port) {
        this.remotePort = port;
        prefs.putInt(PREF_REMOTE_PORT, port);
    }

    /**
     * Returns the effective host to connect to based on current mode.
     */
    public String getEffectiveHost() {
        if (mode == Mode.REMOTE && remoteHost != null && !remoteHost.isBlank()) {
            return remoteHost;
        }
        if (mode == Mode.LAN && lanHost != null && !lanHost.isBlank()) {
            return lanHost;
        }
        return "localhost";
    }

    /**
     * Returns the effective port to connect to based on current mode.
     */
    public int getEffectivePort() {
        if (mode == Mode.REMOTE) {
            return remotePort;
        }
        return ServerConfig.getInstance().getPort();
    }

    /**
     * Returns true if remote mode is configured with a valid host.
     */
    public boolean isRemoteConfigured() {
        return mode == Mode.REMOTE && remoteHost != null && !remoteHost.isBlank();
    }
}
