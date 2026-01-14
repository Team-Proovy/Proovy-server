package com.proovy.global.error;

public class AuthHandler extends RuntimeException {

    private final ErrorStatus status;

    public AuthHandler(ErrorStatus status) {
        super(status.getMessage());
        this.status = status;
    }

    public ErrorStatus getStatus() {
        return status;
    }
}
