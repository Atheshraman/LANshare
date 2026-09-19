package com.example.demo.pc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        System.out.println("[VM ARGS] " + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
        DesktopWindow.springContext = context;
        javafx.application.Application.launch(DesktopWindow.class, args);
    }
}
