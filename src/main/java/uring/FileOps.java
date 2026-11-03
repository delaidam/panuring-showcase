package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Direct glibc downcalls for plain (non-ring) file setup — opening a
 * fixture/target file is not the measured operation, only the ring
 * READ/WRITE around it is. Values confirmed with a C program including
 * {@code <fcntl.h>} on this machine (matches ROADMAP.md §4 step 1).
 */
public final class FileOps {

    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;
    public static final int O_CREAT = 0x40;
    public static final int O_TRUNC = 0x200;
    public static final int O_DIRECT = 0x4000; // only relevant once P1 exercises O_DIRECT, not L2

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle OPEN = downcall("open",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(2),
            Linker.Option.captureCallState("errno"));

    private static final MethodHandle UNLINK = downcall("unlink",
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
            Linker.Option.captureCallState("errno"));

    private FileOps() {
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = LINKER.defaultLookup().find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    /** {@code open()} is variadic in C only because of the optional mode arg — always pass mode here. */
    public static int open(String path, int flags, int mode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cPath = arena.allocateFrom(path);
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int fd = (int) OPEN.invokeExact(capture, cPath, flags, mode);
            if (fd < 0) {
                throw new UringException("open(" + path + ")", Syscalls.errno(capture));
            }
            return fd;
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static void close(int fd) {
        Syscalls.close(fd);
    }

    public static void unlink(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cPath = arena.allocateFrom(path);
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int rc = (int) UNLINK.invokeExact(capture, cPath);
            if (rc < 0) {
                throw new UringException("unlink(" + path + ")", Syscalls.errno(capture));
            }
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(t);
    }

    /** Step 1 check: open/write/close/unlink round-trip via plain downcalls (no ring yet). */
    public static void main(String[] args) throws Exception {
        String path = java.nio.file.Files.createTempFile("uring-panama-fileops-", ".tmp").toString();
        FileOps.unlink(path); // recreate through our own open() below

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        System.out.println("open() -> fd=" + fd);
        close(fd);
        unlink(path);
        System.out.println("PASS: FileOps open/close/unlink OK");

        try {
            open("/nonexistent/path/for/uring-panama-test", O_RDONLY, 0);
            System.out.println("FAIL: expected UringException for missing file");
        } catch (UringException e) {
            System.out.println("caught: " + e.getMessage());
            if (e.errno() == Errno.ENOENT) {
                System.out.println("PASS: negative open() test OK");
            } else {
                System.out.println("FAIL: unexpected errno " + e.errno());
            }
        }
    }
}
