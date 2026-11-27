package com.quizbattle.client.ui;

import com.quizbattle.client.service.AuthenticationFailure;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class UiErrors {
    private UiErrors() { }
    public static String message(Throwable failure, String fallback) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof TimeoutException) { return "The server did not respond in time. Retry, or disconnect if a room request remains pending."; }
        // All IOException futures emitted by the M2 facade are sanitized at its boundary.
        if ((failure instanceof AuthenticationFailure || failure.getClass() == IOException.class) && failure.getMessage() != null) {
            return failure.getMessage();
        }
        return fallback;
    }
}
