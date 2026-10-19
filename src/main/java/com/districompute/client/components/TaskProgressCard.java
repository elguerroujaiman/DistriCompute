package com.districompute.client.components;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;

/**
 * Displays a task card with real-time progress stages and animated completion/failure feedback.
 */
public class TaskProgressCard extends VBox {

    private static final String[] STAGES = {
            "Received", "Validating", "Distributing", "Executing", "Merging", "Finalizing", "Completed"
    };

    private static final double[] STAGE_PROGRESS = {0.08, 0.22, 0.38, 0.55, 0.75, 0.9, 1.0};

    private final Label titleLabel;
    private final Label statusLabel;
    private final Label stageLabel;
    private final Label timeLabel;
    private final ProgressBar progressBar;
    private final HBox stageIndicators;
    private final StackPane resultOverlay;
    private final VBox mainContent;

    private String currentStatus = "PENDING";

    public TaskProgressCard() {
        getStyleClass().add("task-card");
        setSpacing(12);

        // Title row
        titleLabel = new Label("Task Name");
        titleLabel.getStyleClass().add("task-title-label");
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        statusLabel = new Label("PENDING");
        statusLabel.getStyleClass().addAll("status-badge", "status-pending");

        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleRow.getChildren().addAll(titleLabel, spacer, statusLabel);

        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("progress-bar");

        // Stage label
        stageLabel = new Label("Waiting in queue...");
        stageLabel.getStyleClass().add("progress-stage-label");

        // Stage dots
        stageIndicators = new HBox(8);
        stageIndicators.setAlignment(Pos.CENTER_LEFT);
        buildStageIndicators();

        // Time label
        timeLabel = new Label("");
        timeLabel.getStyleClass().add("task-meta-label");

        mainContent = new VBox(10, titleRow, progressBar, stageLabel, stageIndicators, timeLabel);

        // Result overlay (animated success/failure)
        resultOverlay = new StackPane();
        resultOverlay.setVisible(false);
        resultOverlay.setManaged(false);

        getChildren().addAll(mainContent, resultOverlay);
    }

    private void buildStageIndicators() {
        stageIndicators.getChildren().clear();
        for (int i = 0; i < STAGES.length; i++) {
            Circle dot = new Circle(4);
            dot.setId("stage-dot-" + i);
            dot.setFill(Color.web("#555b70"));

            Label lbl = new Label(STAGES[i]);
            lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555b70;");
            lbl.setId("stage-label-" + i);

            VBox stageBox = new VBox(3, dot, lbl);
            stageBox.setAlignment(Pos.CENTER);

            stageIndicators.getChildren().add(stageBox);

            if (i < STAGES.length - 1) {
                Region line = new Region();
                line.setPrefWidth(20);
                line.setPrefHeight(2);
                line.setMaxHeight(2);
                line.setMinHeight(2);
                line.setStyle("-fx-background-color: #555b70; -fx-background-radius: 1;");
                line.setId("stage-line-" + i);
                line.setTranslateY(-8);
                stageIndicators.getChildren().add(line);
            }
        }
    }

    /**
     * Updates the card based on incoming task data map.
     */
    @SuppressWarnings("unchecked")
    public void updateFromMap(Map<String, Object> task) {
        Platform.runLater(() -> {
            String title = (String) task.getOrDefault("title", "Untitled Task");
            String status = (String) task.getOrDefault("status", "PENDING");
            String execTime = (String) task.getOrDefault("formattedExecutionTime", "");
            String error = (String) task.get("errorMessage");

            titleLabel.setText(title);

            if (!status.equals(currentStatus)) {
                currentStatus = status;
                updateStatus(status, execTime, error);
            }
        });
    }

    private void updateStatus(String status, String execTime, String error) {
        // Update status badge
        statusLabel.setText(status);
        statusLabel.getStyleClass().removeIf(c -> c.startsWith("status-"));
        statusLabel.getStyleClass().add("status-" + status.toLowerCase());

        double progress = getProgressForStatus(status);
        animateProgressTo(progress);
        updateStageHighlights(status);

        switch (status) {
            case "PENDING" -> { stageLabel.setText("Waiting in queue..."); timeLabel.setText(""); }
            case "RECEIVED" -> stageLabel.setText("Task received by server...");
            case "VALIDATING" -> stageLabel.setText("Validating JARs and checking Slaves...");
            case "DISTRIBUTING" -> stageLabel.setText("Distributing segments to Slave nodes...");
            case "EXECUTING" -> stageLabel.setText("Slaves executing segments in parallel...");
            case "MERGING" -> stageLabel.setText("Merging results from all Slaves...");
            case "FINALIZING" -> stageLabel.setText("Finalizing distributed results...");
            case "COMPLETED" -> {
                stageLabel.setText("Task completed successfully");
                if (execTime != null && !execTime.isBlank()) timeLabel.setText("Execution time: " + execTime);
                showSuccessAnimation();
            }
            case "FAILED" -> {
                stageLabel.setText("Task failed");
                timeLabel.setText(error != null ? error : "Unknown error");
                showFailureAnimation(false);
            }
            case "CANCELLED" -> {
                stageLabel.setText("Task was cancelled");
                timeLabel.setText("");
            }
        }
    }

    private void animateProgressTo(double target) {
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(500),
                        new KeyValue(progressBar.progressProperty(), target, Interpolator.EASE_BOTH))
        );
        tl.play();
    }

    private void updateStageHighlights(String status) {
        int activeStage = switch (status) {
            case "RECEIVED" -> 0;
            case "VALIDATING" -> 1;
            case "DISTRIBUTING" -> 2;
            case "EXECUTING" -> 3;
            case "MERGING" -> 4;
            case "FINALIZING" -> 5;
            case "COMPLETED" -> 6;
            default -> -1;
        };

        for (int i = 0; i < STAGES.length; i++) {
            VBox stageBox = (VBox) stageIndicators.getChildren().get(i * 2);
            Circle dot = (Circle) stageBox.getChildren().get(0);
            Label lbl = (Label) stageBox.getChildren().get(1);

            if (i < activeStage) {
                dot.setFill(Color.web("#2dd69b"));
                lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #2dd69b;");
            } else if (i == activeStage) {
                dot.setFill(Color.web("#4f8ef7"));
                lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #4f8ef7; -fx-font-weight: bold;");
                pulseAnimation(dot);
            } else {
                dot.setFill(Color.web("#555b70"));
                lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555b70;");
            }
        }
    }

    private void pulseAnimation(Circle dot) {
        ScaleTransition pulse = new ScaleTransition(Duration.millis(700), dot);
        pulse.setFromX(1.0); pulse.setToX(1.5);
        pulse.setFromY(1.0); pulse.setToY(1.5);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        dot.setUserData(pulse);
        pulse.play();
    }

    private void showSuccessAnimation() {
        resultOverlay.getChildren().clear();
        resultOverlay.setVisible(true);
        resultOverlay.setManaged(true);

        FontIcon checkIcon = new FontIcon("fas-check-circle");
        checkIcon.setIconSize(40);
        checkIcon.setIconColor(javafx.scene.paint.Color.web("#2dd69b"));

        Label msg = new Label("Completed Successfully!");
        msg.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2dd69b;");

        VBox box = new VBox(8, checkIcon, msg);
        box.setAlignment(Pos.CENTER);
        box.setOpacity(0);
        resultOverlay.getChildren().add(box);

        FadeTransition fade = new FadeTransition(Duration.millis(400), box);
        fade.setFromValue(0); fade.setToValue(1);

        ScaleTransition scale = new ScaleTransition(Duration.millis(400), checkIcon);
        scale.setFromX(0.3); scale.setToX(1.0);
        scale.setFromY(0.3); scale.setToY(1.0);

        new ParallelTransition(fade, scale).play();

        // Auto-hide after 3s
        PauseTransition pause1 = new PauseTransition(Duration.seconds(3));
        pause1.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), box);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> { resultOverlay.setVisible(false); resultOverlay.setManaged(false); });
            fadeOut.play();
        });
        pause1.play();
    }

    public void showFailureAnimation(boolean isConnectionError) {
        resultOverlay.getChildren().clear();
        resultOverlay.setVisible(true);
        resultOverlay.setManaged(true);

        FontIcon xIcon = new FontIcon("fas-times-circle");
        xIcon.setIconSize(40);
        xIcon.setIconColor(javafx.scene.paint.Color.web("#f5564a"));

        String msg = isConnectionError
                ? "Could not reach the server.\nPlease check your connection."
                : "Task execution failed.\nPlease check your file and try again.";

        Label msgLabel = new Label(msg);
        msgLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f5564a; -fx-text-alignment: center;");
        msgLabel.setWrapText(true);
        msgLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(8, xIcon, msgLabel);
        box.setAlignment(Pos.CENTER);
        box.setOpacity(0);
        resultOverlay.getChildren().add(box);

        // Shake + fade
        FadeTransition fade = new FadeTransition(Duration.millis(400), box);
        fade.setFromValue(0); fade.setToValue(1);

        TranslateTransition shake = new TranslateTransition(Duration.millis(80), box);
        shake.setFromX(-5); shake.setToX(5);
        shake.setCycleCount(4); shake.setAutoReverse(true);

        SequentialTransition anim = new SequentialTransition(fade, shake);
        anim.play();

        PauseTransition pause2 = new PauseTransition(Duration.seconds(4));
        pause2.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), box);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> { resultOverlay.setVisible(false); resultOverlay.setManaged(false); });
            fadeOut.play();
        });
        pause2.play();
    }

    private double getProgressForStatus(String status) {
        return switch (status) {
            case "PENDING" -> 0.0;
            case "RECEIVED" -> 0.08;
            case "VALIDATING" -> 0.22;
            case "DISTRIBUTING" -> 0.38;
            case "EXECUTING" -> 0.55;
            case "MERGING" -> 0.75;
            case "FINALIZING" -> 0.9;
            case "COMPLETED" -> 1.0;
            case "FAILED", "CANCELLED" -> progressBar.getProgress();
            default -> 0.0;
        };
    }
}
