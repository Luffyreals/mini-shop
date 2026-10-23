package com.minishop.user;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional
class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Registers a new USER account and returns its id. The form must already be validated. */
    Long register(RegistrationForm form) {
        String email = EmailNormalizer.normalize(form.email());
        if (users.existsByEmail(email)) {
            log.debug("Registration rejected: duplicate email");
            throw new EmailAlreadyRegisteredException();
        }
        String hash = passwordEncoder.encode(form.password());
        try {
            return users.saveAndFlush(new User(email, hash, form.fullName(), Role.USER)).getId();
        } catch (DataIntegrityViolationException e) {
            if (isEmailUniqueViolation(e)) {
                log.debug("Registration rejected: duplicate email (race)");
                throw new EmailAlreadyRegisteredException(e);
            }
            throw e;
        }
    }

    /**
     * Walks the cause chain to Hibernate's ConstraintViolationException and compares the constraint
     * name case-insensitively. H2 upper-cases the name and may append text, so this uses contains.
     */
    private static boolean isEmailUniqueViolation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name != null && name.toLowerCase(Locale.ROOT).contains(EMAIL_UNIQUE_CONSTRAINT);
            }
        }
        return false;
    }
}
