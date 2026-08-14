package cn.lgs.knowledgebase.infrastructure.security;

import cn.lgs.knowledgebase.application.port.Ports.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class BCryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(char[] password) {
        if (password == null) throw new IllegalArgumentException("password is required");
        return encoder.encode(new String(password));
    }

    @Override
    public boolean matches(char[] password, String encodedHash) {
        if (password == null || encodedHash == null || encodedHash.isBlank()) return false;
        return encoder.matches(new String(password), encodedHash);
    }
}
