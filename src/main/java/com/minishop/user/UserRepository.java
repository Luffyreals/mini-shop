package com.minishop.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Replaces the password hash of one account, matched by (already normalised) email. Only
     * {@link DemoAccountService} calls it. Returns the number of rows updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.passwordHash = :hash where u.email = :email")
    int updatePasswordHash(@Param("email") String email, @Param("hash") String passwordHash);
}
