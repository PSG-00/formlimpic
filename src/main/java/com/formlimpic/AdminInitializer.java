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
 * Automatically initializes the admin account on first run and preserves credentials on restarts.
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
        String customAdminPw = System.getenv("ADMIN_PASSWORD");
        Optional<ReceiptStore.User> existing = store.findUserByUsername("admin");

        if (existing.isEmpty()) {
            String passwordToUse = (customAdminPw != null && !customAdminPw.isBlank())
                    ? customAdminPw.trim()
                    : generateGoogleStylePassword();
            String encoded = encoder.encode(passwordToUse);
            store.registerUser("admin", encoded);

            String banner = String.format("""

                    ================================================================================
                    [Formlimpic] 👑 ADMIN CREDENTIALS INITIALIZED
                    --------------------------------------------------------------------------------
                    Username : admin
                    Password : %s
                    (최초 생성된 관리자 비밀번호입니다.)
                    ================================================================================
                    """, passwordToUse);
            System.out.println(banner);
            log.info("[Formlimpic] Admin account initialized. Username: admin");
        } else {
            if (customAdminPw != null && !customAdminPw.isBlank()) {
                String encoded = encoder.encode(customAdminPw.trim());
                store.updateUserPassword(existing.get().id(), encoded);
                System.out.printf("""

                    ================================================================================
                    [Formlimpic] 👑 ADMIN PASSWORD UPDATED (VIA ENV)
                    --------------------------------------------------------------------------------
                    Username : admin
                    Password : %s
                    ================================================================================
                    %n""", customAdminPw.trim());
            } else {
                System.out.println("""

                    ================================================================================
                    [Formlimpic] 👑 ADMIN ACCOUNT PRESERVED
                    --------------------------------------------------------------------------------
                    Username : admin
                    Password : (기존 설정 비밀번호 유지)
                    ================================================================================
                    """);
            }
            log.info("[Formlimpic] Admin account preserved.");
        }
    }
}
