package com.fleetstore.exception;

public class InvalidTelemetryException extends RuntimeException {
    public InvalidTelemetryException(String message) {
        super(message);
    }
}
