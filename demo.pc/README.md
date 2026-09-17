# 🖧 LAN Share — P2P PC-to-PC File Transfer

A peer-to-peer LAN file-sharing application built with **Spring Boot** (backend) and **JavaFX** (desktop UI).  
It lets two or more PCs on the **same local network** discover each other automatically and transfer files **directly between them**, with no cloud or internet required.

---

## 🧩 How It Works

Each PC runs this application, which starts a local **Spring Boot HTTP server** and a **JavaFX desktop window**.

```
PC A  ──[mDNS discovery]──  PC B
  │                           │
  │  POST /Transfer-Request   │   ← Sender requests to send a file
  │  GET  /transfer-request/{id}/status  ← Sender polls for acceptance
  │  POST /upload/{id}        │   ← File is streamed directly to receiver
  │                           │
```

### Flow (step-by-step)
1. **Discovery** — JmDNS (mDNS/Bonjour) is used to broadcast and detect peers on the LAN automatically.
2. **Transfer Request** — The sender POSTs a transfer request (filename, size, SHA-256 checksum) to the receiver.
3. **Accept / Reject** — The receiver's UI shows an incoming request. The user accepts or rejects it.
4. **Upload** — Once accepted, the sender receives a one-time **Bearer token** and uploads the file directly via HTTP.
5. **Integrity check** — After upload, the receiver recomputes the SHA-256 checksum and compares it. If it mismatches, the file is discarded.
6. **Chunked Transfer** — Files larger than the configured threshold (default 50 MB) are split into chunks and uploaded in parallel for better performance.

---

## 🗂️ Project Structure

```
src/main/java/com/example/demo/pc/
├── Application.java                  # Spring Boot + JavaFX entry point
├── DesktopWindow.java                # JavaFX window (embeds a WebView pointing to localhost)
├── JavaFileBridge.java               # JS↔Java bridge: opens native file picker from WebView
├── client/
│   ├── HttpClientProvider.java       # Shared Java HttpClient (trust-all TLS for LAN)
│   └── TransferClientService.java    # Sender-side logic: request, wait, upload
├── config/
│   ├── cleanupScheduler.java         # Periodically purges expired pending transfers
│   └── webConfig.java                # CORS + rate-limit interceptor registration
├── controller/
│   ├── NetworkController.java        # GET /api/network/self — returns own IP/port
│   ├── PeerController.java           # GET /peers — returns discovered peers
│   ├── SendController.java           # POST /send — initiates outgoing send from the UI
│   └── TransferController.java       # Receiver-side endpoints (request/accept/reject/upload)
├── discovery/
│   ├── PeerDiscoveryService.java     # JmDNS registration + listener
│   ├── PeerInfo.java                 # Record: name, IP, port
│   └── PeerRegistry.java            # In-memory map of discovered peers
├── model/
│   ├── ChunkTransfer.java            # Tracks which chunks have been received
│   ├── PendingTransfer.java          # State machine for a single transfer
│   ├── TransferrequestDTO.java       # JSON body for incoming transfer requests
│   └── TransferStatus.java           # Enum: PENDING, ACCEPTED, REJECTED, EXPIRED
├── security/
│   └── RateLimitInterceptor.java     # Bucket4j rate limiter (per-IP, 10 req/min default)
└── service/
    ├── NetworkService.java            # Detects local LAN IPv4 address
    └── TransferService.java           # Manages pending transfer state, tokens, chunking
```

---

## ⚙️ Configuration (`application.properties`)

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port the server listens on |
| `app.instance-name` | `FILESHARE-PC` | Base name advertised over mDNS |
| `app.storage.download-dir` | `%LOCALAPPDATA%/LANShare/downloads` | Where received files are saved |
| `app.transfer.chunk-size-bytes` | `8388608` (8 MB) | Size of each chunk for large files |
| `app.transfer.chunk-threshold-bytes` | `52428800` (50 MB) | Files above this size are chunked |
| `app.transfer.parallel-chunk-uploads` | `4` | Concurrent chunk upload threads |
| `app.security.token-ttl-seconds` | `120` | Bearer token lifetime after acceptance |
| `app.security.pending-request-ttl-seconds` | `300` | How long a request stays pending before cleanup |
| `app.ratelimit.requests-per-minute` | `10` | Rate limit per IP address |

---

## 🐛 Bugs Found & Fixed

### Bug 1 — Wrong Spring Boot version in `pom.xml`

```diff
- <version>4.1.1</version>
+ <version>3.4.1</version>
```

Spring Boot `4.1.1` does not exist. Maven could not resolve the parent POM — the project **cannot build at all**.

---

### Bug 2 — Non-existent Maven artifact IDs in `pom.xml`

```diff
- <artifactId>spring-boot-starter-webmvc</artifactId>
+ <artifactId>spring-boot-starter-web</artifactId>

# Also removed (these artifacts don't exist in Maven Central):
- spring-boot-starter-cache-test
- spring-boot-starter-validation-test
- spring-boot-starter-webmvc-test
```

The three `-test` variants and `spring-boot-starter-webmvc` are **not real Spring Boot artifact IDs**. Maven fails to resolve them. The correct web starter is `spring-boot-starter-web`, and `spring-boot-starter-test` covers all test needs.

---

### Bug 3 — Wrong Jackson import in `TransferClientService.java`

```diff
- import tools.jackson.databind.ObjectMapper;
+ import com.fasterxml.jackson.databind.ObjectMapper;
```

`tools.jackson` is a **non-existent package**. This causes a **compile error** — the class cannot be compiled.

---

### Bug 4 — Wrong JSON key when reading the requestID from server response

```diff
- return (String) responseBody.get("requestID");
+ return (String) responseBody.get("Request ID");
```

The server responds with the key `"Request ID"` (with a space). The client was looking up `"requestID"` (no space), which always returns `null`, causing a `NullPointerException` — **all file sends fail** after the handshake.

---

### Bug 5 — Missing space in Bearer token Authorization header

```diff
- .header("Authorization", "Bearer" + token)
+ .header("Authorization", "Bearer " + token)
```

The Authorization header value must be `Bearer <space> <token>`. Without the space the token is malformed (e.g., `"Bearersome-uuid-here"`), making the receiver's token check **always return 403 Forbidden** — no upload ever succeeds.

---

### Bug 6 — Mismatched JSON field name in `TransferrequestDTO.java`

```diff
- public record TransferrequestDTO(String filename, long filesize, String checksum)
+ public record TransferrequestDTO(String filename, long fileSize, String checksum)
```

The client sends the JSON key `"fileSize"` (capital `S`), but the DTO declared it as `filesize` (lowercase). Spring's Jackson deserializer is case-sensitive for record components, so the file size was always deserialized as **`0`**, breaking chunking and checksum validation on every transfer.

---

### Bug 7 — JavaFileBridge garbage-collected → `window.javaFileBridge` becomes undefined
**File:** `DesktopWindow.java`

```diff
+ private JavaFileBridge fileBridge;   // strong Java-side reference — prevents GC

  private void attachFileBridge(...) {
-     // anonymous new JavaFileBridge(...) — no Java field holds it
+     fileBridge = new JavaFileBridge(stage, springContext);
+     final JavaFileBridge bridge = fileBridge;
      webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
          if (newState == Worker.State.SUCCEEDED) {
              JSObject window = (JSObject) webView.getEngine().executeScript("window");
-             window.setMember("javaFileBridge", new JavaFileBridge(stage, springContext));
+             window.setMember("javaFileBridge", bridge);
          }
      });
  }
```

**Cause:** `JSObject.setMember()` only keeps a **weak reference** from the JavaScript side. Without a strong Java-side reference, the JVM garbage collector is free to reclaim the `JavaFileBridge` instance at any time (often triggered by the first GC cycle). Once collected, `window.javaFileBridge` in JavaScript resolves to `undefined`, producing the error:

> `s.pickAndSend is not a function. (In 's.pickAndSend(e)', 's.pickAndSend' is undefined)`

The fix stores the bridge in an instance field (`fileBridge`) so the `DesktopWindow` object keeps it alive for its entire lifetime.

---

## 🚀 How to Run

**Prerequisites:** Java 21, Maven 3.9+

```powershell
cd demo.pc
.\mvnw spring-boot:run
```

The app will:
1. Start the Spring Boot server on port `8080`
2. Launch the JavaFX window pointing to `http://localhost:8080/`
3. Begin broadcasting on the LAN via mDNS

Run the same on another PC on the same network — they will discover each other automatically.

---

## 🔥 Firewall

Run `allow-Firewall.bat` **as Administrator** to allow the app through Windows Firewall on port 8080.
