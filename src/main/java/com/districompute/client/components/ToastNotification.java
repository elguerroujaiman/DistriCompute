package com.districompute.client.components;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Animated toast notification that appears at the bottom-right of the screen.
 */
public class ToastNotification {

    public enum ToastType { SUCCESS, ERROR, INFO, WARNING }

    private static VBox toastContainer;
    private static Stage ownerStage;

    public static void setOwnerStage(Stage stage) {
        ownerStage = stage;
        toastContainer = new VBox(8);
        toastContainer.setAlignment(Pos.BOTTOM_RIGHT);
        toastContainer.setPickOnBounds(false);
        toastContainer.setMouseTransparent(false);
    }

    public static void show(String message, ToastType type) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(message, type));
            return;
        }

        HBox toast = createToast(message, type);
        toast.setOpacity(0);

        // Add to a floating overlay (we inject into the scene's root)
        if (ownerStage != null && ownerStage.getScene() != null) {
            Pane root = (Pane) ownerStage.getScene().getRoot();
            root.getChildren().add(toast);

            // Position bottom-right
            toast.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> {
                toast.setLayoutX(root.getWidth() - newVal.getWidth() - 20);
                toast.setLayoutY(root.getHeight() - newVal.getHeight() - 24
                        - (getToastCount(root) - 1) * (newVal.getHeight() + 8));
            });

            root.widthProperty().addListener((obs, o, n) -> {
                toast.setLayoutX(n.doubleValue() - toast.getLayoutBounds().getWidth() - 20);
            });
            root.heightProperty().addListener((obs, o, n) -> {
                toast.setLayoutY(n.doubleValue() - toast.getLayoutBounds().getHeight() - 24);
            });

            animateIn(toast, root);
        }
    }

    private static int getToastCount(Pane root) {
        return (int) root.getChildren().stream()
                .filter(n -> n instanceof HBox && n.getStyleClass().contains("toast"))
                .count();
    }

    private static HBox createToast(String message, ToastType type) {
        HBox toast = new HBox(10);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().addAll("toast", getStyleClass(type));
        toast.setMaxWidth(360);
        toast.setMinHeight(48);

        FontIcon icon = new FontIcon(getIcon(type));
        icon.setIconSize(16);
        icon.setStyle("-fx-icon-color: " + getIconColor(type) + ";");

        Label label = new Label(message);
        label.getStyleClass().add("toast-text");
        label.setWrapText(true);
        label.setMaxWidth(300);

        toast.getChildren().addAll(icon, label);
        return toast;
    }

    private static void animateIn(HBox toast, Pane root) {
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), toast);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(200), toast);
        slideIn.setFromY(20);
        slideIn.setToY(0);

        ParallelTransition intro = new ParallelTransition(fadeIn, slideIn);
        intro.setOnFinished(e -> {
            PauseTransition pause = new PauseTransition(Duration.seconds(3));
            pause.setOnFinished(pe -> animateOut(toast, root));
            pause.play();
        });
        intro.play();
    }

    private static void animateOut(HBox toast, Pane root) {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toast);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> root.getChildren().remove(toast));
        fadeOut.play();
    }

    private static String getStyleClass(ToastType type) {
        return switch (type) {
            case SUCCESS -> "toast-success";
            case ERROR -> "toast-error";
            case WARNING -> "toast-info";
            case INFO -> "toast-info";
        };
    }

    private static String getIcon(ToastType type) {
        return switch (type) {
            case SUCCESS -> "fas-check-circle";
            case ERROR -> "fas-times-circle";
            case WARNING -> "fas-exclamation-triangle";
            case INFO -> "fas-info-circle";
        };
    }

    private static String getIconColor(ToastType type) {
        return switch (type) {
            case SUCCESS -> "#2dd69b";
            case ERROR -> "#f5564a";
            case WARNING -> "#f5a623";
            case INFO -> "#4f8ef7";
        };
    }
}
