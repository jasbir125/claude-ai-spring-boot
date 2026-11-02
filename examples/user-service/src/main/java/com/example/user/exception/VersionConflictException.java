package com.example.user.exception;

public class VersionConflictException extends RuntimeException {

    public VersionConflictException(Long id) {
        super("User " + id + " was modified by someone else; reload it and retry");
    }
}
