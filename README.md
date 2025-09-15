# container-kitty

A lightweight JavaFX application for managing Docker compositions for your project. This tool allows users to select versions, start/stop compositions, and view container statuses without needing to manually handle Docker Compose files.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Setup](#setup)
- [Usage](#usage)
- [Environment Variables](#environment-variables)
- [Error Handling](#error-handling)
- [Developer Notes](#developer-notes)
- [License](#license)

---

## Features

- Fetches available Docker Compose versions directly from a **hosted** `versions.json` file.
- Downloads the selected compose file temporarily for local execution.
- Start and stop compositions with a single click.
- Shows real-time container statuses in a table.
- Logs all actions with timestamps.
- Automatically refreshes container statuses periodically.
- Error popups for invalid actions (no version selected, nothing to stop, failed downloads, etc.).

---

## Requirements

- **Java 17+** (JDK or JRE)
- **Docker** and **Docker Compose** installed and in the system `PATH`.
- Internet connection to fetch compose files from the GitLab repository.

---

## Setup

1. **Download the JAR**  
   Place the `container-kitty-launcher.jar` in a shared folder.

2. **JavaFX**  
   Ensure your JDK/JRE includes JavaFX or is configured to run JavaFX applications. If double-clicking the JAR doesn’t launch the application, run:

   ```java -jar container-kitty-launcher.jar```

## Docker & Docker Compose

Make sure Docker is installed and accessible. Test with:

   ```
   docker --version
   docker compose version
   ```

## Usage

### Initial Launch

On launch, the app fetches available versions from the configured `versions.json` file.

The main window displays:

- **Version selector** – dropdown of available compose versions.
- **Start** – starts the selected version.
- **Stop All** – stops the running composition.
- **Refresh** – updates versions and container statuses.
- **Log area** – shows actions and command output.
- **Container table** – shows running containers (name, image, status).

### Starting a Composition

(1) Select the version from the dropdown.

(2) Click Start.

(3) Logs will show the download progress and Docker Compose startup messages.

### Stopping a Composition

(1) Click Stop All.

If no composition is running, an error popup appears: "No composition is currently running; nothing to stop."

Otherwise, the composition is stopped and the container table is refreshed.

### Refreshing

Click Refresh to:

- Fetch updated versions.json from GitLab.

- Update the dropdown of available versions.

- Refresh the running container table.

### Environment Variables

- **VERSIONS_JSON_URL** (optional): URL to the GitLab-hosted versions.json. 

## Error Handling

- **No version selected** – Shows an error popup and logs the issue.

- **Failed to download compose file** – Shows an error popup with HTTP status or network error.

- **Stop All with nothing running** – Shows an error popup: "No composition is currently running; nothing to stop."

- **Docker errors** – All Docker command output is logged in the log area.

## Developer Notes

- **Temporary Directory** – Compose files are downloaded to a temporary directory which is automatically cleaned up on exit.

- **Periodic Status Refresh** – The container table refreshes every 5 seconds.

- **Logging** – All actions include timestamps.

## Building from Source

(1) Clone the repository.

(2) Ensure Maven or Gradle is installed.

(3) Build the JAR (example with Gradle):

```./gradlew clean build```

(4) Locate the fat JAR in build/libs/container-kitty-launcher-all.jar.
