package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.web.persistence.AppUserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppUserService implements UserDetailsService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public long create(String username, String rawPassword, AppRole role) {
        if (username == null || username.isBlank()) {
            throw new UserValidationException("user.username.blank");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new UserValidationException("user.password.tooShort", MIN_PASSWORD_LENGTH);
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new UserValidationException("user.username.duplicate", username);
        }
        AppUserEntity entity = new AppUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        entity.setRole(role);
        entity.setEnabled(true);
        try {
            return userRepository.save(entity).getId();
        } catch (DataIntegrityViolationException ex) {
            if (isUsernameConstraintViolation(ex)) {
                throw new UserValidationException("user.username.duplicate", username);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<AppUserSummary> list() {
        return userRepository.findAll(Sort.by(Sort.Order.asc("username").ignoreCase())).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public void setRole(long id, AppRole role) {
        AppUserEntity entity = require(id);
        if (role != AppRole.ADMIN) {
            assertNotLastEnabledAdmin(entity);
        }
        entity.setRole(role);
    }

    @Transactional
    public void setEnabled(long id, boolean enabled) {
        AppUserEntity entity = require(id);
        if (!enabled) {
            assertNotLastEnabledAdmin(entity);
        }
        entity.setEnabled(enabled);
    }

    @Transactional
    public void setPassword(long id, String rawPassword) {
        AppUserEntity entity = require(id);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
    }

    @Transactional
    public void delete(long id) {
        AppUserEntity entity = require(id);
        assertNotLastEnabledAdmin(entity);
        userRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public long enabledAdminCount() {
        return userRepository.countByRoleAndEnabled(AppRole.ADMIN, true);
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

    /**
     * D71: the last enabled {@code ADMIN} cannot be disabled, demoted or deleted. The count and
     * the write share one transaction, so they are atomic with respect to each other — the count
     * is the read half of a check-then-act and the transaction makes the whole thing one
     * operation. That does not by itself exclude two concurrent demotions of two different admins:
     * under Postgres' default {@code READ_COMMITTED} each sees a snapshot with both admins and the
     * check passes twice. Hardening would be a pessimistic lock on the admin's row (a
     * {@code SELECT ... FOR UPDATE}); not implemented.
     */
    private void assertNotLastEnabledAdmin(AppUserEntity entity) {
        if (entity.getRole() == AppRole.ADMIN && entity.isEnabled() && enabledAdminCount() <= 1) {
            throw new UserValidationException("user.lastAdmin", entity.getUsername());
        }
    }

    private AppUserEntity require(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Benutzer " + id + " existiert nicht"));
    }

    /**
     * Matches {@code ux_app_user_username} rather than translating any integrity violation into
     * "duplicate username": the insert touches only {@code app_user}, but only a violation of the
     * username unique index is actually a duplicate, and swallowing anything else would hide a
     * real database error.
     */
    private boolean isUsernameConstraintViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cv
                    && "ux_app_user_username".equals(cv.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("ux_app_user_username")) {
                return true;
            }
        }
        return false;
    }

    private AppUserSummary toSummary(AppUserEntity entity) {
        return new AppUserSummary(entity.getId(), entity.getUsername(), entity.getRole(),
                entity.isEnabled(), entity.getCreatedAt());
    }
}
