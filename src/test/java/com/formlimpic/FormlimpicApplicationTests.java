package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;

@SpringBootTest
class FormlimpicApplicationTests {
    @DynamicPropertySource
    static void journal(DynamicPropertyRegistry registry) throws Exception {
        String path = Files.createTempDirectory("formlimpic-context-").resolve("receipts.log").toString();
        registry.add("formlimpic.journal", () -> path);
        // Never write test data into a developer's local PostgreSQL
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:1/none");
    }

    @Test
    void contextLoads() {
    }

}
