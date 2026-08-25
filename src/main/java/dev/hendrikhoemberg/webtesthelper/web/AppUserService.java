package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public long create(String username, String rawPassword, AppRole role) {
        AppUserEntity entity = new AppUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        entity.setRole(role);
        entity.setEnabled(true);
        return userRepository.save(entity).getId();
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return userRepository.count() == 0;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUserEntity entity = userRepository.findByUsernameIgnoreCase(username)
                .filter(AppUserEntity::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("Benutzer nicht gefunden oder deaktiviert: " + username));

        return User.builder()
                .username(entity.getUsername())
                .password(entity.getPasswordHash())
                .authorities("ROLE_" + entity.getRole().name())
                .build();
    }
}
