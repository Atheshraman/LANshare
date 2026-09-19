# LAN Share

A peer-to-peer file-sharing app for local networks. No central server, no internet dependency — machines running this
app discover each other automatically over the LAN and transfer files directly, peer to peer.

Ships as a native Windows desktop app (Spring Boot backend + React frontend, packaged together into a single installer).

## Features

- **Zero-config peer discovery** via mDNS (JmDNS) — no manual IP entry, no central registry. Machines running the app on
  the same network find each other automatically.
- **Accept/reject flow** — incoming transfers require explicit approval before any file data is sent; nothing lands on
  disk without consent.
- **Chunked uploads with resumable retry** for large files, with per-chunk and whole-file SHA-256 checksum verification
  to catch corruption.
- **Rate limiting** (token-bucket, per-IP) on transfer endpoints to prevent abuse.
- **Scheduled cleanup** of expired/abandoned pending transfers.
- **Live progress tracking** — the UI polls real upload progress, not just accept/reject state.
- **Native desktop packaging** — runs as a proper Windows app (`.exe`), not a browser tab: a bundled JRE, an embedded UI
  window (JavaFX `WebView`), and a native file picker.

## Architecture

```
┌─────────────────────────┐         mDNS (JmDNS)        ┌─────────────────────────┐
│   PC A — LAN Share.exe   │◄──────── discovery ────────►│   PC B — LAN Share.exe   │
│                          │                              │                          │
│  React UI (WebView)      │                              │  React UI (WebView)      │
│         │                │                              │         │                │
│  Spring Boot (:8080) ────┼───── direct HTTP transfer ───┼──► Spring Boot (:8080)   │
└─────────────────────────┘                              └─────────────────────────┘
```

Each machine runs its own full instance — backend and frontend together, one process, one port. There's no server anyone
has to host; every peer is self-contained. The React frontend is built and served *from* Spring Boot
(`src/main/resources/static/`), so the UI and API share one origin.

### Transfer flow

1. Sender registers a transfer request with the receiver (`POST /Transfer-Request`) — filename, size, checksum, no file
   data yet.
2. Receiver sees it in their pending list and accepts or rejects.
3. On accept, the receiver issues a short-lived token.
4. Sender uploads the file — in one stream if small, or in parallel checksummed chunks if it's above the configured
   threshold — authenticated with that token.
5. Receiver verifies the final checksum before keeping the file; a mismatch discards it.

## Tech stack

- **Backend:** Java, Spring Boot, JmDNS (peer discovery), Bucket4j (rate limiting), Java's built-in `HttpClient`
  (outbound peer-to-peer calls).
- **Frontend:** React (Vite), plain CSS.
- **Desktop packaging:** JavaFX (`WebView`, embedded UI window),
  `jpackage` (native installer with bundled JRE).

## Prerequisites

- JDK 17+ (JDK 23 used in development) for the backend.
- Node.js 18+ and npm for the frontend.
- WiX Toolset (Windows only, for building the `.exe`/`.msi` installer via
  `jpackage`).

## Running it locally (development)

**Backend:**

```
mvn clean package
java -jar target/<your-jar-name>.jar
```

Starts Spring Boot on `http://localhost:8080` and opens the app window.

**Frontend (if iterating on UI separately):**

```
cd frontend
npm install
npm run dev
```

Runs a hot-reloading dev server at `http://localhost:3000`, talking to the backend on `8080`. This is for frontend
development only — the shipped app serves the built frontend directly from the backend (see below).

## Building the full package

```
cd frontend
npm run build
```

Copy the contents of `frontend/dist/` into `src/main/resources/static/`, then:

```
mvn clean package
jpackage --input target --name "LAN Share" --main-jar <your-jar-name>.jar \
  --main-class com.example.demo.pc.Application --type exe \
  --icon app-icon.ico --win-shortcut --win-menu
```

Produces a Windows installer with a bundled JRE — no separate Java install required on the machine it's run on.

## Configuration

Key properties in `application.properties`:

| Property                                   | Purpose                                                                               |
|--------------------------------------------|---------------------------------------------------------------------------------------|
| `server.address`                           | `0.0.0.0` to accept connections from other machines on the LAN (not just `localhost`) |
| `server.port`                              | Port the app listens on (default `8080`)                                              |
| `app.storage.download-dir`                 | Where received files are saved                                                        |
| `app.transfer.chunk-size-bytes`            | Chunk size for large-file uploads                                                     |
| `app.transfer.chunk-threshold-bytes`       | File size above which chunked upload kicks in                                         |
| `app.transfer.parallel-chunk-uploads`      | Concurrent chunk uploads                                                              |
| `app.ratelimit.requests-per-minute`        | Per-IP rate limit on transfer endpoints                                               |
| `app.security.token-ttl-seconds`           | How long an accept token stays valid                                                  |
| `app.security.pending-request-ttl-seconds` | How long an unanswered transfer request stays pending before expiring                 |

## Known limitations

- Discovery is LAN-only by design (mDNS doesn't route over the internet) — this is a local-network tool, not a cloud
  service.
- JavaFX's `WebView` doesn't support the browser's native file picker for
  `<input type="file">`; the app works around this with a JavaFX
  `FileChooser` bridged into the page's JS.
- Windows Firewall may prompt on first run; allow access on the **Private**
  network profile for discovery and transfers to work.

## License

*(add your license here)*
