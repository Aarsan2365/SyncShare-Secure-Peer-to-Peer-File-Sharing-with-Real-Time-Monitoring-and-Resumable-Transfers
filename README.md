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

## Building & Testing

```bash
mvn clean test
mvn clean package
```
