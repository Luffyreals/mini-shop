package com.minishop.admin;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the Windows file chooser in the admin smoke tests (the native dialog cannot be driven without a
 * robot). It is registered through {@code new TestShellApp(StubImageFileDialog.class)} and wins over the real
 * {@link ImageFileDialog} bean because of {@link Primary}. A test tells it which file the "user" picks with
 * {@link #willChoose(Path)}; {@code null} means the user cancels the chooser.
 */
@Component
@Primary
class StubImageFileDialog extends ImageFileDialog {

    private volatile Path next;
    private final AtomicInteger calls = new AtomicInteger();

    void willChoose(Path file) {
        this.next = file;
    }

    int calls() {
        return calls.get();
    }

    void reset() {
        this.next = null;
        this.calls.set(0);
    }

    @Override
    Optional<Path> choose() {
        calls.incrementAndGet();
        return Optional.ofNullable(next);
    }
}
