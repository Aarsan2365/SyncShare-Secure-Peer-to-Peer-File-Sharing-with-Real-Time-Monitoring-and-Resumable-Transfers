SyncShare :

Secure-Peer-to-Peer-File-Sharing-with-Real-Time-Monitoring-and-Resumable-Transfers

SyncShare is a Java-based peer-to-peer file-sharing application designed for secure transfers over local networks, featuring Mutual TLS 1.3 encryption, automatic peer discovery, real-time progress monitoring, resumable transfers, persistent transfer history, and SHA-256 integrity verification.

## Persistent Transfer History

- **Storage Format**: Lightweight schema-versioned JSON (`transfer-history.json`) with zero external JSON dependencies.
- **Storage Location**:
  - Windows: `%LOCALAPPDATA%\SyncShare\transfer-history.json`
  - macOS: `~/Library/Application Support/SyncShare/transfer-history.json`
  - Linux / Unix: `$XDG_DATA_HOME/syncshare/transfer-history.json` (or `~/.local/share/syncshare/transfer-history.json`)
  - Optional overrides via JVM system properties `-Dsyncshare.dataDir=<dir>` or `-Dsyncshare.historyFile=<file>`.
- **Recorded Events**: Captures only terminal outcomes (`COMPLETED`, `FAILED`, `CANCELLED`) from live transfer lifecycle events, deduplicated by transfer ID, without storing passwords, private keys, tokens, file contents, or full local directory paths.
- **Safety & Reliability**: Atomic temp-file writes with channel sync (`ATOMIC_MOVE` with safe replacement fallback), non-blocking JavaFX Application Thread execution, corruption detection without overwriting valid data, and explicit user confirmation before clearing saved history.

## Application Settings Module

- **Storage Format**: Lightweight schema-versioned JSON (`settings.json`) with zero external JSON dependencies.
- **Storage Location**:
  - Windows: `%LOCALAPPDATA%\SyncShare\settings.json`
  - macOS: `~/Library/Application Support/SyncShare/settings.json`
  - Linux / Unix: `$XDG_DATA_HOME/syncshare/settings.json` (or `~/.local/share/syncshare/settings.json`)
  - Optional overrides via JVM system properties `-Dsyncshare.dataDir=<dir>` or `-Dsyncshare.settingsFile=<file>`.
- **Configurable Preferences**:
  - **Storage**: Default download directory and default save location with directory validation, missing directory auto-creation, and non-writable directory detection.
  - **Application**: Startup behavior (`autoStartReceiver`, `startMinimized`), approval requirements (`requireApproval`), and SHA-256 verification (`verifySha256`).
  - **Notifications**: Notification toggles (`notificationsEnabled`, `notifyOnTransferComplete`, `notifyOnIncomingRequest`, `soundEnabled`).
  - **Appearance**: Informative theme indicators reflecting the active application theme (`DEFAULT`). Nonfunctional theme controls are omitted in accordance with the single-theme design system.
  - **Network Configuration**: Editable fields (`deviceAlias`, `listeningPort`) with live synchronization into `DeviceInfoService` and `ConnectionService`. Security and system parameters (`ipAddress`, `operatingSystem`, TLS certificate paths, TLS summary) remain strictly read-only.
- **Safety & Security Guarantees**:
  - **Zero Credential Leakage**: Plaintext passwords, private keys, authentication tokens, and TLS keystore credentials are never written to settings files.
  - **Atomic Persistence**: Atomic write via temporary file replacement (`ATOMIC_MOVE` with safe fallback) ensuring valid configuration files are never corrupted by aborted writes.
  - **Corruption Recovery**: Corrupted or malformed settings files are safely quarantined while in-memory fallback defaults are applied with clear diagnostic feedback.
  - **Non-blocking IO**: All disk persistence operations execute asynchronously off the JavaFX Application Thread.

## Building & Testing

```bash
mvn clean test
mvn clean package
```
