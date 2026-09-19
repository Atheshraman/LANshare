package com.example.demo.pc;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import org.springframework.context.ConfigurableApplicationContext;

public class DesktopWindow extends javafx.application.Application {

    static ConfigurableApplicationContext springContext;

    // Held as a field so the JVM garbage collector does NOT reclaim it.
    // JSObject.setMember() only keeps a weak JS-side reference; without this
    // strong Java-side reference the GC can collect the bridge at any time,
    // making window.javaFileBridge undefined in JavaScript.
    private JavaFileBridge fileBridge;

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        webView.getEngine().load("http://localhost:8080/");
        attachFileBridge(webView, stage, springContext);
        Scene scene = new Scene(webView, 1100, 750);
        stage.setTitle("LAN Share");
        stage.setScene(scene);

        stage.setOnCloseRequest(event -> {
            if (springContext != null) {
                springContext.close();
            }
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    private void attachFileBridge(WebView webView, Stage stage, ConfigurableApplicationContext springContext) {
        fileBridge = new JavaFileBridge(stage, springContext);
        final JavaFileBridge bridge = fileBridge;
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("javaFileBridge", bridge);
            }
        });
    }
}
