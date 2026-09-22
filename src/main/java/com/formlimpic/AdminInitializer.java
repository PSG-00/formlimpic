package com.formlimpic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Automatically initializes or refreshes the admin account with a secure Google-style random password on startup.
 */
@Component
@Order(1)
public class AdminInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final ReceiptStore store;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public AdminInitializer(ReceiptStore store, PasswordEncoder encoder) {
        this.store = store;
        this.encoder = encoder;
    }

    public String generateGoogleStylePassword() {
        StringBuilder sb = new StringBuilder();
        for (int block = 0; block < 4; block++) {
            if (block > 0) sb.append("-");
            for (int i = 0; i < 4; i++) {
                sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
        }
        return sb.toString();
    }

    @Override
    public void run(ApplicationArguments args) {
        String randomPassword = generateGoogleStylePassword();
        String encoded = encoder.encode(randomPassword);

        Optional<ReceiptStore.User> existing = store.findUserByUsername("admin");
        if (existing.isEmpty()) {
            store.registerUser("admin", encoded);
        } else {
            store.updateUserPassword(existing.get().id(), encoded);
        }

        String banner = String.format("""

                ================================================================================
                [Formlimpic] 👑 ADMIN CREDENTIALS INITIALIZED
                --------------------------------------------------------------------------------
                Username : admin
                Password : %s
                (서버 실행 시 자동 생성된 안전한 무작위 관리자 비밀번호입니다.)
                ================================================================================
                """, randomPassword);
        System.out.println(banner);
        log.info("[Formlimpic] Admin account initialized. Username: admin");
    }
}
