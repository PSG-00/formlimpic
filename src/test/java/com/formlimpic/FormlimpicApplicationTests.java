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
    }

    @Test
    void contextLoads() {
    }

}
