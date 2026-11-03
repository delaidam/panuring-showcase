package uring;

/** Names errno values relevant to L1/L2 (no complete table — only what actually shows up). */
public final class Errno {

    public static final int EPERM = 1;
    public static final int ENOENT = 2;
    public static final int EINTR = 4;
    public static final int EBADF = 9;
    public static final int ENOMEM = 12;
    public static final int EFAULT = 14;
    public static final int EBUSY = 16;
    public static final int EINVAL = 22;
    public static final int ECANCELED = 125;

    private Errno() {
    }

    public static String nameOf(int errno) {
        return switch (errno) {
            case EPERM -> "EPERM";
            case ENOENT -> "ENOENT";
            case EINTR -> "EINTR";
            case ENOMEM -> "ENOMEM";
            case EBADF -> "EBADF";
            case EFAULT -> "EFAULT";
            case EINVAL -> "EINVAL";
            case EBUSY -> "EBUSY";
            case ECANCELED -> "ECANCELED";
            default -> "errno=" + errno;
        };
    }
}
