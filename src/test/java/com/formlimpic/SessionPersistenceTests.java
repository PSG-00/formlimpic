package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Deploys restart the server (systemctl stop/restart); logged-in users should stay logged in. */
class SessionPersistenceTests {
    @TempDir
    Path dir;

    @Test
    void loginSurvivesGracefulRestart() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        String[] args = {
                "--server.port=" + port,
                "--formlimpic.journal=" + dir.resolve("receipts.log"),
                "--server.servlet.session.store-dir=" + dir.resolve("sessions"),
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none",
        };
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port + "/api";

        String cookie;
        try (ConfigurableApplicationContext ignored = SpringApplication.run(FormlimpicApplication.class, args)) {
            HttpResponse<String> signup = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/signup"))
                    .header("Content-Type", "application/json")
                    .header("X-Formlimpic", "formlimpic")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"keep_me\",\"password\":\"secret123\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signup.statusCode(), signup.body());
            cookie = signup.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith("JSESSIONID="))
                    .map(c -> c.split(";")[0])
                    .findFirst().orElseThrow();
        }

        try (ConfigurableApplicationContext ignored = SpringApplication.run(FormlimpicApplication.class, args)) {
            HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/me"))
                    .header("Cookie", cookie)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            assertTrue(me.body().contains("\"loggedIn\":true"), me.body());
            assertTrue(me.body().contains("keep_me"), me.body());
        }
    }
}
