package com.districompute.client;

import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Manages the application theme (dark/light).
 * Detects the system's preferred theme on startup.
 * Persists user preference using Java Preferences API.
 */
public class ThemeManager {

    public enum Theme { DARK, LIGHT }

    private static final String PREF_KEY = "theme";
    private static final Preferences prefs = Preferences.userNodeForPackage(ThemeManager.class);

    private static Theme currentTheme;
    private static final List<Scene> registeredScenes = new ArrayList<>();
    private static final List<Runnable> themeChangeListeners = new ArrayList<>();

    static {
        currentTheme = loadTheme();
    }

    /**
     * Detects the system's preferred theme.
     * Uses the OS color scheme if available.
     */
    public static Theme detectSystemTheme() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        try {
            if (osName.contains("win")) {
                // Windows: check registry for dark mode
                Process p = Runtime.getRuntime().exec(
                        "reg query HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize /v AppsUseLightTheme");
                byte[] bytes = p.getInputStream().readAllBytes();
                String result = new String(bytes);
                if (result.contains("0x0")) return Theme.DARK;
                return Theme.LIGHT;
            } else if (osName.contains("mac")) {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"});
                byte[] bytes = p.getInputStream().readAllBytes();
                String result = new String(bytes).trim();
                return result.equalsIgnoreCase("dark") ? Theme.DARK : Theme.LIGHT;
            }
        } catch (Exception ignored) {}
        return Theme.DARK; // Default to dark
    }

    private static Theme loadTheme() {
        String saved = prefs.get(PREF_KEY, null);
        if (saved != null) {
            try { return Theme.valueOf(saved); } catch (IllegalArgumentException ignored) {}
        }
        return detectSystemTheme();
    }

    public static Theme getCurrentTheme() { return currentTheme; }

    public static boolean isDark() { return currentTheme == Theme.DARK; }

    public static void setTheme(Theme theme) {
        currentTheme = theme;
        prefs.put(PREF_KEY, theme.name());
        applyToAll();
        themeChangeListeners.forEach(Runnable::run);
    }

    public static void toggleTheme() {
        setTheme(currentTheme == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    public static void registerScene(Scene scene) {
        registeredScenes.add(scene);
        applyToScene(scene);
    }

    public static void unregisterScene(Scene scene) {
        registeredScenes.remove(scene);
    }

    public static void addThemeChangeListener(Runnable listener) {
        themeChangeListeners.add(listener);
    }

    private static void applyToAll() {
        for (Scene scene : registeredScenes) applyToScene(scene);
    }

    public static void applyToScene(Scene scene) {
        scene.getStylesheets().clear();
        String cssFile = currentTheme == Theme.DARK
                ? "/styles/dark.css"
                : "/styles/light.css";
        String cssUrl = ThemeManager.class.getResource(cssFile).toExternalForm();
        scene.getStylesheets().add(cssUrl);
    }
}
