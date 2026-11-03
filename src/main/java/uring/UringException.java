package uring;

/** Thrown when a raw syscall returns an error; carries the errno value for diagnostics. */
public final class UringException extends RuntimeException {

    private final int errno;

    public UringException(String syscall, int errno) {
        super(syscall + " failed, errno=" + errno + " (" + Errno.nameOf(errno) + ")");
        this.errno = errno;
    }

    public int errno() {
        return errno;
    }
}
