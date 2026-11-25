package com.p2s.agent.sh;

/** A command failed (bad usage, missing file, ...). Writes of the whole command line are discarded. */
public final class ShellError extends RuntimeException {
    public ShellError(String message) {
        super(message);
    }
}
