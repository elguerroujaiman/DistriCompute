# DistriCompute V2

DistriCompute is a Java desktop application for submitting Java tasks to a master server and distributing work across worker nodes. The client uses JavaFX; the master and workers communicate over TCP and persist account, task, and log state as local JSON files.

This is an educational project and a prototype. Read the security notes before running it on a network.

## Features

- JavaFX client with dashboard, task submission, task history, logs, user management, and dark/light themes.
- Master server with registration, BCrypt password hashes, task queueing, and JSON persistence.
- Worker nodes that execute task JARs and report results to the master.
- Splitter JAR support for dividing a task into segments and assigning them to multiple workers.
- Included sample task and splitter JARs for a local walkthrough.

## Requirements

- JDK 17 or newer.
- Maven 3.8 or newer.
- A desktop session for running the JavaFX client.

## Run locally

Open three terminals in the project directory.

### 1. Start the master server

```powershell
mvn exec:java "-Dexec.mainClass=com.districompute.server.MasterServer"
```

By default, the master accepts client connections on port `9090` and worker connections on port `9091`.

### 2. Start one worker

```powershell
mvn exec:java "-Dexec.mainClass=com.districompute.server.SlaveServer" "-Dexec.args=localhost 9092"
```

Start additional workers in separate terminals with `localhost 9093` and `localhost 9094`. The worker port argument identifies its slot; in dual-port mode workers connect to the master's worker listener on `9091`.

### 3. Start the desktop client

```powershell
mvn javafx:run
```

Register an account in the client. The first account created on a fresh server is assigned the administrator role. Submit `sample-task.jar` with `sample-splitter.jar` and select the number of workers to try distributed execution.

To use a single master port instead, start the master with `"-Dexec.args=--unified"` and start each worker with `"-Dexec.args=localhost 9092 --unified"` (change the slot port for additional workers).

## Build and verify

```powershell
mvn test
mvn package
```

The project currently has no automated test sources; `mvn test` verifies that the application sources compile.

## Data and privacy

The server creates `data/` as needed and writes `users.json`, `tasks.json`, and `logs.json` there while it runs. These are local runtime files and are ignored by Git. They can contain BCrypt account hashes, uploaded task JAR contents, usernames, and activity logs. A fresh clone starts with empty state.

## Security notes

Do not expose this prototype to the public internet or run it with untrusted users or task JARs.

- The network protocol uses Java object serialization over raw TCP without TLS. Credentials and task data are not encrypted in transit, and untrusted Java deserialization needs stronger defenses before production use.
- Submitted task JARs execute as operating-system processes with the worker user's permissions. The timeout is not a security sandbox; task code can access files and resources available to that account.
- The master listens on network interfaces selected by the operating system. Use it only on a trusted, isolated network and configure host firewalls accordingly.
- The first user to register on a fresh server becomes administrator. Run the server in a trusted environment during initial setup.

## Project layout

```text
src/main/java/com/districompute/
|-- client/       JavaFX application, screens, and controllers
|-- common/       Models, configuration, logging, and patterns
|-- dao/          JSON-backed data access
|-- network/      Client-side TCP connection
\-- server/       Master, worker, task queue, and execution strategies
src/main/resources/  JavaFX styles and application icon
tools/sample-jars/   Source for the included sample JARs
data/                Local runtime state (created automatically)
```
