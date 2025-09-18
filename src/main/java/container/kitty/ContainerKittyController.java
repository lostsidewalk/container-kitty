package container.kitty;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ContainerKittyController {

    // Remote URL for production
    private static final String VERSIONS_JSON_URL =
            "https://gitlab.com/<namespace>/<repo>/-/raw/main/docker/compose/versions.json";

    // Dev resource path (classpath)
    private static final String DEV_VERSIONS_JSON_RESOURCE = "dev-versions.json"; // dev-versions.json

    private static final String DOCKER_CMD = "docker";
    private static final String COMPOSE_CMD = "compose";
    private static final String UP_CMD = "up";
    private static final String DOWN_CMD = "down";

    private static class VersionsManifest {
        public List<Composition> compositions;
        public List<Version> versions;
    }

    @FXML private Label statusLabel;
    @FXML private ComboBox<Version> versionComboBox;
    @FXML private ComboBox<Composition> compositionComboBox;
    @FXML private TextArea logArea;
    @FXML private TableView<ContainerInfo> containerTable;
    @FXML private TableColumn<ContainerInfo, String> nameColumn;
    @FXML private TableColumn<ContainerInfo, String> imageColumn;
    @FXML private TableColumn<ContainerInfo, String> memUsageColumn;
    @FXML private TableColumn<ContainerInfo, String> statusColumn;
    @FXML private Button startButton;
    @FXML private Button stopAllButton;
    @FXML private Label compositionCommentLabel;
    @FXML private Label versionCommentLabel;

    // local cache of lists
    private List<Composition> availableCompositions = List.of();
    private List<Version> availableVersions = List.of();
    private Timeline statusUpdater;
    private File tempComposeDir;
    private File activeComposeFile;

    @FXML
    private void handleAbout() {
        String tempDirPath = (tempComposeDir != null) ? tempComposeDir.getAbsolutePath() : "Not initialized";
        String dockerPath = detectDockerPath();

        String content = """
        Container Kitty Launcher
        Version: 1.0.0
        Author: Your Team
        Built with JavaFX

        Java Runtime: %s
        JavaFX Runtime: %s
        OS: %s %s (%s)
        Docker Executable: %s

        Docker Compose Versions JSON:
        %s

        Temporary Compose Directory:
        %s
        """.formatted(
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version", "Unknown"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                dockerPath,
                VERSIONS_JSON_URL,
                tempDirPath
        );

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12;");
        textArea.setPrefWidth(500);
        textArea.setPrefHeight(300);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Container Kitty");
        alert.setHeaderText("Container Kitty Launcher");
        alert.getDialogPane().setContent(textArea);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        // Load your logo from resources
        try {
            InputStream logoStream = getClass().getResourceAsStream("/container/kitty/logo.png");
            if (logoStream != null) {
                javafx.scene.image.Image logo = new javafx.scene.image.Image(logoStream);
                ImageView imageView = new ImageView(logo);
                imageView.setFitWidth(64);
                imageView.setFitHeight(64);
                alert.setGraphic(imageView);
            }
        } catch (Exception e) {
            appendLog("Failed to load About logo: " + e.getMessage());
        }

        alert.showAndWait();
    }

    private String detectDockerPath() {
        String dockerPath = "Not found";

        try {
            String[] cmd;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "where", "docker.exe"};
            } else {
                cmd = new String[]{"which", "docker"};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    dockerPath = line;
                }
            }

            process.waitFor();

        } catch (IOException | InterruptedException e) {
            dockerPath = "Error detecting docker path: " + e.getMessage();
        }

        return dockerPath;
    }

    @FXML
    private void handleClearLogs() {
        logArea.clear();
    }

    @FXML
    private void handleStart() {
        Composition composition = compositionComboBox.getValue();
        Version version = versionComboBox.getValue();

        if (composition == null) {
            showError("No composition selected.");
            return;
        }
        if (version == null) {
            showError("No version selected.");
            return;
        }

        runCommandAsync(() -> {
            try {
                File composeFile = downloadComposeFile(composition.getName(), version.getIdent());
                if (composeFile == null) {
                    String msg = "Failed to download compose file for " + composition + " " + version;
                    appendLog("ERROR: " + msg);
                    showError(msg);
                    return;
                }

                String[] cmd = { "docker", "compose", "-f", composeFile.getAbsolutePath(), "up", "-d" };
                _runCommand(cmd);
                appendLog("Started " + composition + " version " + version);
            } catch (IOException e) {
                String msg = "Error starting composition: " + e.getMessage();
                appendLog("ERROR: " + msg);
                showError(msg);
            }
        });
    }

    @FXML
    private void handleStopAll() {
        runCommandAsync(() -> {
            if (activeComposeFile != null && activeComposeFile.exists()) {
                String[] cmd = {DOCKER_CMD, COMPOSE_CMD, "-f", activeComposeFile.getAbsolutePath(), DOWN_CMD};
                _runCommand(cmd);
                appendLog("Took down entire composition.");
                activeComposeFile = null;
            } else {
                String msg = "No composition is currently running; nothing to stop.";
                appendLog("ERROR: " + msg);
                showError(msg);
            }
            refreshContainers();
        });
    }

    @FXML
    private void handleRefresh() {
        runCommandAsync(() -> {
            try {
                VersionsManifest manifest = fetchVersionManifest();
                if (manifest == null
                        || manifest.compositions == null || manifest.compositions.isEmpty()
                        || manifest.versions == null || manifest.versions.isEmpty()) {
                    appendLog("No compositions or versions available from server.");
                    showError("No compositions or versions available from server.");
                    return;
                }

                compositionCommentMap.clear();
                versionCommentMap.clear();

                for (Composition comp : manifest.compositions) {
                    compositionComboBox.getItems().add(comp);
                    compositionCommentMap.put(comp.getName(), comp.getComment());
                }

                for (Version ver : manifest.versions) {
                    versionComboBox.getItems().add(ver);
                    versionCommentMap.put(ver.getIdent(), ver.getComment());
                }

                availableCompositions = manifest.compositions;
                availableVersions = manifest.versions;

                Platform.runLater(() -> {
                    compositionComboBox.getItems().setAll(availableCompositions);
                    compositionComboBox.getSelectionModel().selectFirst();

                    versionComboBox.getItems().setAll(availableVersions);
                    versionComboBox.getSelectionModel().selectFirst();
                });

                appendLog("Refreshed compositions and versions.");
            } catch (IOException e) {
                appendLog("ERROR fetching manifest: " + e.getMessage());
                showError("Failed to fetch compositions/versions: " + e.getMessage());
            }

            refreshContainers();
        });
    }

    private final Map<String, String> compositionCommentMap = new HashMap<>();
    private final Map<String, String> versionCommentMap = new HashMap<>();

    @FXML
    public void initialize() {
        // Disable controls initially
        startButton.setDisable(true);
        stopAllButton.setDisable(true);

        memUsageColumn.setCellValueFactory(data -> data.getValue().memUsageProperty());

        // Enable Start/Stop only when both composition and version are selected
        Runnable updateControls = () -> {
            boolean enabled = compositionComboBox.getValue() != null && versionComboBox.getValue() != null;
            startButton.setDisable(!enabled);
            stopAllButton.setDisable(!enabled);
        };
        compositionComboBox.valueProperty().addListener((obs, oldV, newV) -> updateControls.run());
        versionComboBox.valueProperty().addListener((obs, oldV, newV) -> updateControls.run());

        compositionComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String comment = compositionCommentMap.get(newVal.getName()); // Map<String,String>
                compositionCommentLabel.setText(comment != null ? comment : "");
                updateControls.run();
            }
        });

        versionComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String comment = versionCommentMap.get(newVal.getIdent()); // Map<String,String>
                versionCommentLabel.setText(comment != null ? comment : "");
                updateControls.run();
            }
        });

        compositionCommentLabel.textProperty().addListener((obs, oldText, newText) -> {
            Tooltip t = compositionCommentLabel.getTooltip();
            if (t == null) {
                t = new Tooltip(newText);
                compositionCommentLabel.setTooltip(t);
            } else {
                t.setText(newText);
            }
        });
        versionCommentLabel.textProperty().addListener((obs, oldText, newText) -> {
            Tooltip t = versionCommentLabel.getTooltip();
            if (t == null) {
                t = new Tooltip(newText);
                versionCommentLabel.setTooltip(t);
            } else {
                t.setText(newText);
            }
        });

        containerTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Table bindings
        nameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        imageColumn.setCellValueFactory(data -> data.getValue().imageProperty());
        memUsageColumn.setCellValueFactory(data -> data.getValue().memUsageProperty());
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
            String msg = "Cannot create temporary folder for compose files: " + e.getMessage();
            appendLog("ERROR: " + msg);
            showError(msg);
        }

        refreshContainers();

        statusUpdater = new Timeline(new KeyFrame(Duration.seconds(5), event -> {
            refreshContainers();
            updateStatus();
        }));
        statusUpdater.setCycleCount(Timeline.INDEFINITE);
        statusUpdater.play();

        handleRefresh();
    }

    /** Fetch available compositions and versions (remote or dev resource in dev mode) */
    private VersionsManifest fetchVersionManifest() throws IOException {
        InputStream in = ContainerKittyController.class.getResourceAsStream(DEV_VERSIONS_JSON_RESOURCE);
        if (in != null) {
            appendLog("Loading versions.json from dev resource: " + DEV_VERSIONS_JSON_RESOURCE);
        } else {
            appendLog("Loading versions.json from remote: " + VERSIONS_JSON_URL);
            URL url = new URL(VERSIONS_JSON_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            in = conn.getInputStream();
        }
        InputStream inputStream = in;

        try (inputStream) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, VersionsManifest.class);
        }
    }

    /** Downloads a compose file for the given composition+version to temp directory */
    private File downloadComposeFile(String composition, String version) throws IOException {
        // clean old .yml files optionally
        File[] oldFiles = tempComposeDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (oldFiles != null) {
            for (File f : oldFiles) f.delete();
        }

        // MUST match naming convention in repo
        String fileName = String.format("docker-compose-%s-%s.yml", composition, version);
        String urlStr = VERSIONS_JSON_URL.replace("versions.json", fileName);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() != 200) {
            appendLog("Failed to download " + fileName + ": HTTP " + conn.getResponseCode());
            return null;
        }

        File outFile = new File(tempComposeDir, fileName);
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

    /** Updates status label with running containers count and tooltip for non-running containers */
    private void updateStatus() {
        List<ContainerInfo> containers = containerTable.getItems();

        long running = containers.stream()
                .filter(c -> c.getStatus().startsWith("Up")).count();
        long total = containers.size();

        String statusText;
        String style;

        if (total == 0) {
            statusText = "Status: Stopped";
            style = "-fx-text-fill: red; -fx-font-weight: bold;";
        } else {
            statusText = "Status: " + running + "/" + total + " running";
            style = running == total
                    ? "-fx-text-fill: green; -fx-font-weight: bold;"
                    : "-fx-text-fill: orange; -fx-font-weight: bold;";
        }

        List<Label> containerLabels = containers.stream()
                .filter(c -> !c.getStatus().startsWith("Up"))
                .map(c -> {
                    Label lbl = new Label(c.getName() + " (" + c.getStatus() + ")");
                    lbl.setStyle("-fx-text-fill: red;");
                    return lbl;
                })
                .toList();

        Platform.runLater(() -> {
            statusLabel.setText(statusText);
            statusLabel.setStyle(style);

            if (!containerLabels.isEmpty()) {
                VBox tooltipBox = new VBox(2);
                tooltipBox.getChildren().addAll(containerLabels);

                ScrollPane scrollPane = new ScrollPane(tooltipBox);
                scrollPane.setPrefWidth(250);
                scrollPane.setPrefHeight(150);
                scrollPane.setFitToWidth(true);

                Tooltip tooltip = new Tooltip();
                tooltip.setGraphic(scrollPane);
                tooltip.setText(""); // no text, only graphic
                statusLabel.setTooltip(tooltip);
            } else {
                statusLabel.setTooltip(null);
            }
        });
    }

    private void refreshContainers() {
        // Remember currently selected container name
        ContainerInfo selected = containerTable.getSelectionModel().getSelectedItem();
        String selectedName = selected != null ? selected.getName() : null;

        Platform.runLater(() -> containerTable.getItems().clear());

        try {
            ProcessBuilder pb = new ProcessBuilder(DOCKER_CMD, "ps", "--format", "{{.Names}}|{{.Image}}|{{.Status}}");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 3) {
                        ContainerInfo container = new ContainerInfo(parts[0], parts[1], parts[2], null);
                        Platform.runLater(() -> {
                            containerTable.getItems().add(container);
                            if (selectedName != null && selectedName.equals(container.getName())) {
                                containerTable.getSelectionModel().select(container);
                            }
                        });
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
