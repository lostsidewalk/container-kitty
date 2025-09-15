package container.kitty;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

public class ContainerKittyController {

    private static final String VERSIONS_JSON_URL =
            "https://gitlab.com/<namespace>/<repo>/-/raw/main/docker/compose/versions.json";

    @FXML private Label statusLabel;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;

    private Timeline statusUpdater;
    private File tempComposeDir;

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

        runCommandAsync(() -> {
            try {
                File composeFile = downloadComposeFile(version);
                if (composeFile == null) {
                    appendLog("ERROR: Failed to download compose file for version: " + version);
                    showError("Failed to download compose file for version: " + version);
                    return;
                }

                String[] cmd = {
                        "docker", "compose",
                        "-f", composeFile.getAbsolutePath(),
                        "up", "-d"
                };
                _runCommand(cmd);
                appendLog("Started version: " + version);

            } catch (IOException e) {
                appendLog("ERROR: " + e.getMessage());
                showError("Error starting version: " + e.getMessage());
            }
        });
    }

    @FXML
    private void handleStopAll() {
        runCommandAsync(() -> {
            if (tempComposeDir != null) {
                File[] files = tempComposeDir.listFiles((d, name) -> name.endsWith(".yml"));
                if (files != null && files.length > 0) {
                    String[] cmd = { "docker", "compose", "-f", files[0].getAbsolutePath(), "down" };
                    _runCommand(cmd);
                    appendLog("Took down entire composition.");
                } else {
                    String msg = "No composition is currently running; nothing to stop.";
                    appendLog("ERROR: " + msg);
                    showError(msg);   // popup
                }
            } else {
                String msg = "Temporary compose directory not initialized; cannot stop composition.";
                appendLog("ERROR: " + msg);
                showError(msg);   // popup
            }
            refreshContainers();
        });
    }

    @FXML
    private void handleRefresh() {
        runCommandAsync(() -> {
            try {
                List<String> versions = fetchAvailableVersions();
                if (versions.isEmpty()) {
                    appendLog("No versions available from server.");
                    showError("No versions available from server.");
                    return;
                }
                Platform.runLater(() -> {
                    versionComboBox.getItems().setAll(versions);
                    versionComboBox.getSelectionModel().selectFirst();
                });
                appendLog("Refreshed versions list.");
            } catch (IOException e) {
                appendLog("ERROR fetching versions: " + e.getMessage());
                showError("Failed to fetch available versions: " + e.getMessage());
            }

            refreshContainers();
        });
    }

    @FXML
    public void initialize() {
        // Table bindings
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());
        statusColumn.setCellValueFactory(data -> data.getValue().statusProperty());

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

        try {
            tempComposeDir = Files.createTempDirectory("docker-compose-temp").toFile();
            tempComposeDir.deleteOnExit();
        } catch (IOException e) {
            appendLog("ERROR creating temp directory: " + e.getMessage());
            showError("Cannot create temporary folder for compose files.");
        }

        refreshContainers();

        // Periodic status updater
        statusUpdater = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            refreshContainers();
            updateStatus();
        }));
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();

        // Initial fetch of available versions
        handleRefresh();
    }

    /** Downloads a compose file for the given version to the temp directory */
    private File downloadComposeFile(String version) throws IOException {
        String urlStr = VERSIONS_JSON_URL.replace("versions.json", "docker-compose-" + version + ".yml");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() != 200) {
            appendLog("Failed to download: HTTP " + conn.getResponseCode());
            return null;
        }

        File outFile = new File(tempComposeDir, "docker-compose-" + version + ".yml");
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        return outFile;
    }

    /** Fetch available versions from versions.json in GitLab */
    private List<String> fetchAvailableVersions() throws IOException {
        URL url = new URL(VERSIONS_JSON_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP " + conn.getResponseCode());
        }

        try (InputStream in = conn.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, new TypeReference<>() {
            });
        }
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

    /** Runs a Runnable asynchronously with control disabling */
    private void runCommandAsync(Runnable task) {
        new Thread(() -> {
            setControlsDisabled(true);
            try {
                task.run();
            } finally {
                setControlsDisabled(false);
            }
        }).start();
    }

    /** Enables/disables controls on FX thread */
    private void setControlsDisabled(boolean disabled) {
        Platform.runLater(() -> {
            versionComboBox.setDisable(disabled);
            containerTable.setDisable(disabled);
        });
    }

    /** Internal command runner */
    private void _runCommand(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
        }
    }

    /** Shows an error dialog */
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.showAndWait();
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
