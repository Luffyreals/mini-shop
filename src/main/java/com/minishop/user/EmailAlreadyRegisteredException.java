package com.minishop.user;

/** Thrown when registering an email that already has an account. The message never contains the email. */
class EmailAlreadyRegisteredException extends RuntimeException {

    EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }

    EmailAlreadyRegisteredException(Throwable cause) {
        super("Email is already registered", cause);
    }
}
