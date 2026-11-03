package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Raw FFM downcall layer. glibc does not expose io_uring_* wrappers
 * (verified: {@code nm -D /lib64/libc.so.6} is empty for io_uring), so we
 * go through {@code syscall(2)}, which is variadic in C — hence
 * {@link Linker.Option#firstVariadicArg(int)} on every handle below.
 */
public final class Syscalls {

    // x86_64, from /usr/include/asm/unistd_64.h
    public static final long SYS_io_uring_setup = 425;
    public static final long SYS_io_uring_enter = 426;
    public static final long SYS_io_uring_register = 427;

    // x86_64 Linux mmap constants
    public static final int PROT_READ = 1;
    public static final int PROT_WRITE = 2;
    public static final int MAP_SHARED = 0x01;
    public static final int MAP_POPULATE = 0x8000;

    // mmap returns -1 as a pointer on error (not NULL)
    public static final MemorySegment MAP_FAILED = MemorySegment.ofAddress(-1L);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE =
            CAPTURE_LAYOUT.varHandle(PathElement.groupElement("errno"));

    private static final MethodHandle GETPID = downcall("getpid",
            FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle CLOSE = downcall("close",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT),
            Linker.Option.captureCallState("errno"));

    private static final MethodHandle MMAP = downcall("mmap",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG),
            Linker.Option.captureCallState("errno"));

    private static final MethodHandle MUNMAP = downcall("munmap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG),
            Linker.Option.captureCallState("errno"));

    private static final int RLIMIT_MEMLOCK = 8;

    private static final MethodHandle GETRLIMIT = downcall("getrlimit",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS),
            Linker.Option.captureCallState("errno"));

    // syscall(425 /* io_uring_setup */, entries, params) -> fd
    private static final MethodHandle SYS_SETUP = downcall("syscall",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS),
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));

    // syscall(426 /* io_uring_enter */, fd, toSubmit, minComplete, flags, sig, sigsz)
    private static final MethodHandle SYS_ENTER = downcall("syscall",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG),
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));

    // syscall(427 /* io_uring_register */, fd, opcode, arg, nrArgs)
    private static final MethodHandle SYS_REGISTER = downcall("syscall",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));

    private Syscalls() {
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = LIBC.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    /** {@code getpid()} — no errno capture, used only as a smoke test for the downcall mechanics. */
    public static int getpid() {
        try {
            return (int) GETPID.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static int close(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            int rc = (int) CLOSE.invokeExact(capture, fd);
            if (rc < 0) {
                throw new UringException("close", errno(capture));
            }
            return rc;
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static MemorySegment mmap(long length, int prot, int flags, int fd, long offset) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            MemorySegment result = (MemorySegment) MMAP.invokeExact(
                    capture, MemorySegment.NULL, length, prot, flags, fd, offset);
            if (result.address() == MAP_FAILED.address()) {
                throw new UringException("mmap", errno(capture));
            }
            return result;
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static void munmap(MemorySegment addr, long length) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            int rc = (int) MUNMAP.invokeExact(capture, addr, length);
            if (rc < 0) {
                throw new UringException("munmap", errno(capture));
            }
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    /** @return fd of the new ring (rc &gt;= 0); caller checks/throws using {@link #errno}. */
    public static long ioUringSetup(MemorySegment capture, int entries, MemorySegment params) {
        try {
            return (long) SYS_SETUP.invokeExact(capture, SYS_io_uring_setup, entries, params);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static long ioUringEnter(MemorySegment capture, int fd, int toSubmit, int minComplete, int flags) {
        try {
            return (long) SYS_ENTER.invokeExact(
                    capture, SYS_io_uring_enter, fd, toSubmit, minComplete, flags, MemorySegment.NULL, 0L);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static long ioUringRegister(MemorySegment capture, int fd, int opcode, MemorySegment arg, int nrArgs) {
        try {
            return (long) SYS_REGISTER.invokeExact(capture, SYS_io_uring_register, fd, opcode, arg, nrArgs);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    /** Current {@code RLIMIT_MEMLOCK} soft limit in bytes ({@code struct rlimit.rlim_cur}) — the ulimit -l trap for registered buffers. */
    public static long getrlimitMemlockBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rlim = arena.allocate(16); // struct rlimit { rlim_cur; rlim_max; }, both u64
            MemorySegment capture = newCaptureSegment(arena);
            int rc = (int) GETRLIMIT.invokeExact(capture, RLIMIT_MEMLOCK, rlim);
            if (rc < 0) {
                throw new UringException("getrlimit(RLIMIT_MEMLOCK)", errno(capture));
            }
            return rlim.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    public static MemorySegment newCaptureSegment(Arena arena) {
        return arena.allocate(CAPTURE_LAYOUT);
    }

    public static int errno(MemorySegment captureSegment) {
        return (int) ERRNO_HANDLE.get(captureSegment, 0L);
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(t);
    }

    /** Step 1 check: downcall to getpid must return the same PID as {@link ProcessHandle}. */
    public static void main(String[] args) {
        int viaSyscall = getpid();
        long viaJdk = ProcessHandle.current().pid();
        System.out.printf("getpid() via FFM downcall = %d, ProcessHandle.current().pid() = %d%n",
                viaSyscall, viaJdk);
        if (viaSyscall != viaJdk) {
            throw new AssertionError("PID mismatch: downcall=" + viaSyscall + " jdk=" + viaJdk);
        }
        System.out.println("PASS: Syscalls smoke test (getpid round-trip) OK");
    }
}
