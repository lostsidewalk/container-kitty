package com.example;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class DockerComposeLauncherController {

    @FXML private Label statusLabel;

    private Timeline statusUpdater;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    @FXML
    private void handleStart() {
        String version = versionComboBox.getValue();
        File versionFile = new File("docker-compose-" + version + ".yml");
        if (!versionFile.exists()) {
            appendLog("Version file not found: " + versionFile.getName());
            showError("Cannot start: version file does not exist.");
            return;
        }

        runCommandAsync(new String[]{
                "docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose-" + version + ".yml", "up", "-d"
        }, () -> appendLog("Started version: " + version)); // log after completion
    }

    @FXML
    private void handleStopAll() {
        runCommandAsync(
                new String[]{"docker", "compose", "-f", "docker-compose.yml", "down"},
                () -> {
                    appendLog("Took down entire composition.");
                    refreshContainers(); // update table after stopping
                }
        );
    }

    @FXML
    private void handleRefresh() {
        refreshContainers();
        appendLog("Refreshed versions and containers.");
    }

    @FXML
    public void initialize() {
        // Populate version combo
        versionComboBox.getItems().addAll("latest", "1.0.0", "1.1.0");
        versionComboBox.getSelectionModel().selectFirst();

        // Table bindings
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());

        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if (status.startsWith("Up")) {
                        setStyle("-fx-text-fill: green;");
                    } else {
                        setStyle("-fx-text-fill: red;");
                    }
                }
            }
        });
        statusColumn.setCellValueFactory(data -> data.getValue().statusProperty());

        refreshContainers();

        // Periodic status updater
        statusUpdater = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            refreshContainers();
            updateStatus();
        }));
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();
    }

    private void updateStatus() {
        long running = containerTable.getItems().stream()
                .filter(c -> c.getStatus().startsWith("Up")).count();
        long total = containerTable.getItems().size();

        if (total == 0) {
            statusLabel.setText("Status: Stopped");
            statusLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("Status: " + running + "/" + total + " running");
            statusLabel.setStyle(running == total ?
                    "-fx-text-fill: green; -fx-font-weight: bold;" :
                    "-fx-text-fill: orange; -fx-font-weight: bold;");
        }
    }

    private void refreshContainers() {
        Platform.runLater(() -> containerTable.getItems().clear());

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}"
            );
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        ContainerInfo container = new ContainerInfo(parts[0], parts[1], parts[2]);
                        Platform.runLater(() -> containerTable.getItems().add(container));
                    }
                }
            }
        } catch (IOException e) {
            appendLog("Error fetching containers: " + e.getMessage());
        }
    }

    private void runCommandAsync(String[] command, Runnable onComplete) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                _runCommand(command);
                if (onComplete != null) {
                    Platform.runLater(onComplete);
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private void _runCommand(String[] command) {
        setControlsDisabled(true);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }

            int exitCode = process.waitFor();
            appendLog("Command exited with code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            appendLog("Error: " + e.getMessage());
            showError(e.getMessage());
        } finally {
            setControlsDisabled(false);
        }
    }

    private void setControlsDisabled(boolean disabled) {
        Platform.runLater(() -> {
            versionComboBox.setDisable(disabled);
            containerTable.setDisable(disabled);
        });
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.showAndWait();
        });
    }

    private void appendLog(String message) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            logArea.appendText(timestamp + " - " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
}
