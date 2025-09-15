package com.example;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.io.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class DockerComposeLauncherController {

    private static final String ENV_COMPOSE_DIR = "DOCKER_COMPOSE_DIR";

    @FXML private Label statusLabel;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;

    private Timeline statusUpdater;
    private File composeDir;

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    @FXML
    private void handleStart() {
        String version = versionComboBox.getValue();
        if (version == null || version.isEmpty()) {
            showError("No version selected.");
            return;
        }

        File versionFile = new File(composeDir, "docker-compose-" + version + ".yml");
        if (!versionFile.exists()) {
            showError("Version file not found: " + versionFile.getName());
            appendLog("ERROR: Version file not found: " + versionFile.getName());
            return;
        }

        runCommandAsync(new String[]{
                "docker", "compose", "-f", "docker-compose.yml", "-f", versionFile.getAbsolutePath(), "up", "-d"
        }, () -> appendLog("Started version: " + version));
    }

    @FXML
    private void handleStopAll() {
        runCommandAsync(
                new String[]{"docker", "compose", "-f", "docker-compose.yml", "down"},
                () -> {
                    appendLog("Took down entire composition.");
                    refreshContainers();
                }
        );
    }

    @FXML
    private void handleRefresh() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "pull");
            pb.directory(composeDir);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }

            int exitCode = process.waitFor();
            appendLog("Git pull exited with code: " + exitCode);
        } catch (IOException | InterruptedException e) {
            appendLog("Error pulling compose repo: " + e.getMessage());
            showError("Error pulling compose repo: " + e.getMessage());
        }

        populateVersions();
        refreshContainers();
        appendLog("Refreshed versions and containers.");
    }

    @FXML
    public void initialize() {
        // Determine compose directory
        String dirPath = System.getenv(ENV_COMPOSE_DIR);
        if (dirPath == null || dirPath.isEmpty()) {
            showErrorAndExit("Environment variable " + ENV_COMPOSE_DIR + " is not set. " +
                    "Please set it to the local clone of the compose repo.");
            return;
        }

        composeDir = new File(dirPath);
        if (!composeDir.exists() || !composeDir.isDirectory()) {
            showErrorAndExit("Compose directory is invalid: " + composeDir.getAbsolutePath());
            return;
        }

        appendLog("Using compose directory: " + composeDir.getAbsolutePath());
        populateVersions();

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

    /** Populates the version combo box from docker-compose-*.yml files in the compose directory */
    private void populateVersions() {
        File[] files = composeDir.listFiles((dir, name) -> name.startsWith("docker-compose-") && name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            versionComboBox.getItems().clear();
            appendLog("No compose files found in directory: " + composeDir.getAbsolutePath());
            return;
        }
        List<String> versions = Arrays.stream(files)
                .map(f -> f.getName().replace("docker-compose-", "").replace(".yml", ""))
                .sorted()
                .collect(Collectors.toList());
        versionComboBox.getItems().setAll(versions);
        versionComboBox.getSelectionModel().selectFirst();
        appendLog("Available versions: " + String.join(", ", versions));
    }

    /** Updates status label with running containers count */
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

    /** Refreshes the table of running containers */
    private void refreshContainers() {
        Platform.runLater(() -> containerTable.getItems().clear());

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}"
            );
            pb.directory(composeDir);
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

    /** Runs a command asynchronously and invokes onComplete on the FX thread */
    private void runCommandAsync(String[] command, Runnable onComplete) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                _runCommand(command);
                if (onComplete != null) {
                    Platform.runLater(onComplete);
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    /** Internal command runner */
    private void _runCommand(String[] command) {
        setControlsDisabled(true);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(composeDir);
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

    /** Shows an error dialog */
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.showAndWait();
        });
    }

    private void showErrorAndExit(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.showAndWait();  // wait for user to click OK
            Platform.exit();       // exit after dismissal
        });
    }

    /** Appends a log message with timestamp */
    private void appendLog(String message) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            logArea.appendText(timestamp + " - " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
}
