package com.flunav.backend.exception;

// Use a RuntimeException so we don't have to add "throws" everywhere
public class DuplicateItemException extends RuntimeException {
    public DuplicateItemException(String message) {
        super(message);
    }
}