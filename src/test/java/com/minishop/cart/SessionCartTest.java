package com.minishop.cart;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Giỏ của khách trong session (BR-004-1, BR-004-2, AC-004-12, spec 004 §7). */
class SessionCartTest {

    @Test
    void put_thenQuantityOf_returnsQuantityAndZeroForMissing() {
        SessionCart cart = new SessionCart();

        cart.put(1L, 3);

        assertThat(cart.quantityOf(1L)).isEqualTo(3);
        assertThat(cart.quantityOf(2L)).isZero();
    }

    @Test
    void put_sameProductAgain_overwritesAndKeepsOriginalPosition() {
        SessionCart cart = new SessionCart();
        cart.put(10L, 1);
        cart.put(20L, 2);
        cart.put(30L, 3);

        cart.put(10L, 7);

        assertThat(cart.quantityOf(10L)).isEqualTo(7);
        assertThat(cart.lines().keySet()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void lines_keepInsertionOrder() {
        SessionCart cart = new SessionCart();
        cart.put(30L, 1);
        cart.put(10L, 1);
        cart.put(20L, 1);

        assertThat(List.copyOf(cart.lines().keySet())).containsExactly(30L, 10L, 20L);
    }

    @Test
    void put_withQuantityZeroOrNegative_throws() {
        SessionCart cart = new SessionCart();

        assertThatThrownBy(() -> cart.put(1L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cart.put(1L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    void remove_missingProduct_isNoOp() {
        SessionCart cart = new SessionCart();
        cart.put(1L, 2);

        cart.remove(99L);

        assertThat(cart.lines()).containsExactly(Map.entry(1L, 2));
    }

    @Test
    void remove_existingProduct_removesLine() {
        SessionCart cart = new SessionCart();
        cart.put(1L, 2);
        cart.put(2L, 3);

        cart.remove(1L);

        assertThat(cart.quantityOf(1L)).isZero();
        assertThat(cart.lines().keySet()).containsExactly(2L);
    }

    @Test
    void totalQuantity_andIsEmpty_reflectContent() {
        SessionCart cart = new SessionCart();
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.totalQuantity()).isZero();

        cart.put(1L, 4);
        cart.put(2L, 1);

        assertThat(cart.isEmpty()).isFalse();
        assertThat(cart.totalQuantity()).isEqualTo(5);
    }

    @Test
    void lines_isUnmodifiableCopy() {
        SessionCart cart = new SessionCart();
        cart.put(1L, 2);
        Map<Long, Integer> snapshot = cart.lines();

        assertThatThrownBy(() -> snapshot.put(2L, 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove(1L)).isInstanceOf(UnsupportedOperationException.class);
        cart.put(3L, 1);

        assertThat(snapshot).containsExactly(Map.entry(1L, 2));
        assertThat(cart.lines().keySet()).containsExactly(1L, 3L);
    }

    @Test
    void serializeRoundTrip_keepsContentAndOrder() throws Exception {
        SessionCart cart = new SessionCart();
        cart.put(30L, 1);
        cart.put(10L, 5);
        cart.put(20L, 2);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(cart);
        }
        SessionCart copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (SessionCart) in.readObject();
        }

        assertThat(copy).isNotSameAs(cart);
        assertThat(List.copyOf(copy.lines().entrySet()))
                .containsExactly(Map.entry(30L, 1), Map.entry(10L, 5), Map.entry(20L, 2));
        assertThat(copy.totalQuantity()).isEqualTo(8);
    }
}
