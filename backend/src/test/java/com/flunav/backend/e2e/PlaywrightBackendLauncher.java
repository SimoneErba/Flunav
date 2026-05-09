package com.flunav.backend.e2e;

import com.flunav.backend.BackendApplication;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public final class PlaywrightBackendLauncher {
    private static final int SERVER_PORT = 18080;

    private PlaywrightBackendLauncher() {
    }

    public static void main(String[] args) {
        TestContainersEnvironment.start();
        Runtime.getRuntime().addShutdownHook(new Thread(TestContainersEnvironment::stop));

        TestContainersEnvironment.springProperties(SERVER_PORT)
                .forEach((key, value) -> System.setProperty(key, String.valueOf(value)));

        SpringApplication application = new SpringApplication(BackendApplication.class);
        ConfigurableApplicationContext context = application.run(args);

        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        System.out.println("E2E_BACKEND_READY http://localhost:" + SERVER_PORT);
    }
}
