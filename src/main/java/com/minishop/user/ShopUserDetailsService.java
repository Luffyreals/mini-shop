package com.minishop.user;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users from the database for form login. Being the only {@link UserDetailsService} bean, it
 * also switches off Spring Boot's generated in-memory user.
 */
@Service
@Transactional(readOnly = true)
class ShopUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    ShopUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByEmail(EmailNormalizer.normalize(username))
                .map(ShopUserDetailsService::toCurrentUser)
                // No email in the message: keep personal data out of logs.
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private static CurrentUser toCurrentUser(User user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getFullName(), user.getRole());
    }
}
