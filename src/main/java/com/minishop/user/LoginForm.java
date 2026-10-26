package com.minishop.user;

/**
 * What the login dialog submits. The password is never cleaned (spaces and unusual characters may be part of it) and
 * is never printed; the email is personal data too, so {@code toString} prints neither.
 */
record LoginForm(String email, String password) {

    LoginForm {
        email = email == null ? "" : email;
        password = password == null ? "" : password;
    }

    @Override
    public String toString() {
        return "LoginForm[email=***, password=***]";
    }
}
