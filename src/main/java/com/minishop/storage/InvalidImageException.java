package com.minishop.storage;

/** File ảnh không hợp lệ. Message viết bằng tiếng Anh cho log; controller dựa vào {@link #reason()}. */
public final class InvalidImageException extends RuntimeException {

    public enum Reason { UNSUPPORTED_TYPE, TOO_LARGE }

    private final Reason reason;

    public InvalidImageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
