package com.example.demo.pc;

import com.example.demo.pc.client.TransferClientService;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.context.ApplicationContext;

import java.io.File;

/**
 * Exposed to the page's JS as window.javaFileBridge (see DesktopWindow).
 * WebView can't use the native browser file-picker for <input type="file">
 * (see the "fileID(PlatformFileHandle) NOT IMPLEMENTED" log line), so this
 * bridges to JavaFX's real FileChooser instead, and — since we're in the
 * same JVM as Spring — kicks off the transfer directly via the existing
 * TransferClientService bean rather than routing through an HTTP upload
 * from inside the WebView.
 */
public class JavaFileBridge {
    private final Stage stage;
    private final ApplicationContext springContext;

    public JavaFileBridge(Stage stage, ApplicationContext springContext) {
        this.stage = stage;
        this.springContext = springContext;
    }

    /**
     * Called from JS as: window.javaFileBridge.pickAndSend(peerUrl)
     * Must run on the JavaFX Application Thread (WebView JS calls already
     * happen on it, so this is safe as-is). Returns a JSON string —
     * kept as a plain String rather than a Java object because that's
     * what's straightforward to consume back in JS via JSON.parse().
     */
    public String pickAndSend(String peerUrl) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a file to send");
        File selected = chooser.showOpenDialog(stage);

        if (selected == null) {
            return "{\"status\":\"cancelled\"}";
        }

        String filename = escapeJson(selected.getName());
        TransferClientService transferClientService = springContext.getBean(TransferClientService.class);

        try {
            String requestId = transferClientService.initiateTransfer(peerUrl, selected.toPath());

            Thread.ofVirtual().start(() -> {
                try {
                    transferClientService.completeTransfer(peerUrl, requestId, selected.toPath());
                    System.out.println("[Send] Completed: " + selected.getName() + " -> " + peerUrl);
                } catch (Exception e) {
                    System.err.println("[Send] Failed: " + selected.getName() + " -> " + peerUrl + " : " + e.getMessage());
                }
            });

            return String.format(
                    "{\"status\":\"started\",\"filename\":\"%s\",\"requestId\":\"%s\",\"peerUrl\":\"%s\"}",
                    filename, requestId, escapeJson(peerUrl)
            );
        } catch (Exception e) {
            return String.format("{\"status\":\"error\",\"message\":\"%s\"}", escapeJson(String.valueOf(e.getMessage())));
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

