package com.p2s.agent.vfs;

/** A file operation failed; the message is shown to the model as is. */
public final class VfsException extends Exception {
    public VfsException(String message) {
        super(message);
    }
}
