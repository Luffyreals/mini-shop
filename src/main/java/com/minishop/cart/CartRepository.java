package com.minishop.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);
}
