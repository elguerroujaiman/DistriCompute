package com.districompute.client.components;

import javafx.animation.*;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Full-screen semi-transparent loading overlay.
 *
 * Usage:
 *   LoadingOverlay overlay = new LoadingOverlay();
 *   myRootStackPane.getChildren().add(overlay); // add as top layer
 *
 *   overlay.show("Authenticating...");  // show with message
 *   overlay.hide();                     // hide when done
 */
public class LoadingOverlay extends StackPane {

    private final Label messageLabel;
    private final RotateTransition spinnerAnimation;

    public LoadingOverlay() {
        // Semi-transparent dark background
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.45);");
        setVisible(false);
        setManaged(false);

        // Spinner — two arcs to create a ring effect
        Circle track = new Circle(22);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.web("#dde1ec"));
        track.setStrokeWidth(4);

        Arc spinner = new Arc(0, 0, 22, 22, 90, 270);
        spinner.setType(ArcType.OPEN);
        spinner.setFill(Color.TRANSPARENT);
        spinner.setStroke(Color.web("#4f8ef7"));
        spinner.setStrokeWidth(4);
        spinner.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

        StackPane spinnerPane = new StackPane(track, spinner);
        spinnerPane.setPrefSize(50, 50);

        // Rotation animation
        spinnerAnimation = new RotateTransition(Duration.millis(800), spinner);
        spinnerAnimation.setByAngle(-360);
        spinnerAnimation.setCycleCount(Animation.INDEFINITE);
        spinnerAnimation.setInterpolator(Interpolator.LINEAR);

        // Message label
        messageLabel = new Label("Loading...");
        messageLabel.getStyleClass().add("page-subtitle");

        // Card container
        VBox card = new VBox(16, spinnerPane, messageLabel);
        card.setAlignment(Pos.CENTER);
        card.setStyle(
                "-fx-background-color: -dc-bg-card;" +
                        "-fx-background-radius: 14;" +
                        "-fx-padding: 32 40 32 40;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 6);"
        );

        setAlignment(Pos.CENTER);
        getChildren().add(card);

        // Fade in/out transitions
        setOpacity(0);
    }

    /**
     * Shows the overlay with a custom message and starts the spinner.
     */
    public void show(String message) {
        javafx.application.Platform.runLater(() -> {
            messageLabel.setText(message != null ? message : "Loading...");
            setVisible(true);
            setManaged(true);
            spinnerAnimation.play();

            FadeTransition fadeIn = new FadeTransition(Duration.millis(150), this);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();
        });
    }

    /**
     * Hides the overlay and stops the spinner.
     */
    public void hide() {
        javafx.application.Platform.runLater(() -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), this);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> {
                setVisible(false);
                setManaged(false);
                spinnerAnimation.stop();
            });
            fadeOut.play();
        });
    }

    /**
     * Updates the message text while the overlay is visible.
     */
    public void setMessage(String message) {
        javafx.application.Platform.runLater(() -> messageLabel.setText(message));
    }
}
