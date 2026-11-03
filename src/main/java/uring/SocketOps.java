package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Linux IPv4/TCP setup calls made through glibc FFM downcalls.
 *
 * <p>This class deliberately owns only synchronous descriptor setup and the
 * tiny direct client used by {@link SocketEchoDemo}. Network I/O intended to
 * scale is prepared through {@link Sqe#prepAccept}, {@link Sqe#prepRecv},
 * and {@link Sqe#prepSend}; those buffers must remain live until the ring
 * reports their CQEs. The synchronous {@link #sendAll} and
 * {@link #receiveExactly} methods use a segment only for the duration of
 * their own native call.
 *
 * <p>Constants and {@code sockaddr_in} byte order are Linux ABI values,
 * verified against this repository's x86_64 Linux target. This is not a
 * portable BSD/Windows socket binding.
 */
public final class SocketOps {

    public static final int AF_INET = 2;
    public static final int SOCK_STREAM = 1;
    public static final int SOCK_NONBLOCK = 0x800;
    public static final int SOCK_CLOEXEC = 0x80000;
    public static final int IPPROTO_TCP = 6;
    public static final int SOL_SOCKET = 1;
    public static final int SO_REUSEADDR = 2;
    /** Suppresses process-wide SIGPIPE for the direct client send path. */
    public static final int MSG_NOSIGNAL = 0x4000;
    public static final int SOCKADDR_IN_SIZE = 16;
    public static final int INADDR_LOOPBACK = 0x7F00_0001;

    private static final ValueLayout.OfShort NETWORK_SHORT = JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt NETWORK_INT = JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle SOCKET = downcall("socket",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle BIND = downcall("bind",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle LISTEN = downcall("listen",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle CONNECT = downcall("connect",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle GETSOCKNAME = downcall("getsockname",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle SETSOCKOPT = downcall("setsockopt",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle SEND = downcall("send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle RECV = downcall("recv",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
            Linker.Option.captureCallState("errno"));

    private SocketOps() {
    }

    /** Opens a blocking IPv4 TCP socket with close-on-exec set atomically. */
    public static int tcpSocket() {
        return socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    }

    public static int socket(int domain, int type, int protocol) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int fd = (int) SOCKET.invokeExact(capture, domain, type, protocol);
            if (fd < 0) {
                throw new UringException("socket", Syscalls.errno(capture));
            }
            return fd;
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    /** Sets {@code SO_REUSEADDR}; useful for fixed-port service demos. */
    public static void setReuseAddress(int fd, boolean enabled) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            value.set(JAVA_INT, 0, enabled ? 1 : 0);
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int rc = (int) SETSOCKOPT.invokeExact(capture, fd, SOL_SOCKET, SO_REUSEADDR, value, Integer.BYTES);
            requireSuccess("setsockopt(SO_REUSEADDR)", rc, capture);
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    /** Binds {@code fd} to 127.0.0.1:{@code port}; port zero asks the kernel for an ephemeral port. */
    public static void bindLoopback(int fd, int port) {
        bindIpv4(fd, INADDR_LOOPBACK, port);
    }

    public static void bindIpv4(int fd, int ipv4Address, int port) {
        try (Arena arena = Arena.ofConfined()) {
            bind(fd, sockaddrIn(arena, ipv4Address, port));
        }
    }

    public static void bind(int fd, MemorySegment address) {
        Objects.requireNonNull(address, "address");
        if (address.byteSize() < SOCKADDR_IN_SIZE) {
            throw new IllegalArgumentException("sockaddr must be at least " + SOCKADDR_IN_SIZE + " bytes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int rc = (int) BIND.invokeExact(capture, fd, address, SOCKADDR_IN_SIZE);
            requireSuccess("bind", rc, capture);
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    public static void listen(int fd, int backlog) {
        if (backlog <= 0) {
            throw new IllegalArgumentException("backlog must be positive: " + backlog);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int rc = (int) LISTEN.invokeExact(capture, fd, backlog);
            requireSuccess("listen", rc, capture);
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    /** Connects a blocking client socket to 127.0.0.1:{@code port}. */
    public static void connectLoopback(int fd, int port) {
        try (Arena arena = Arena.ofConfined()) {
            connect(fd, sockaddrIn(arena, INADDR_LOOPBACK, port));
        }
    }

    public static void connect(int fd, MemorySegment address) {
        Objects.requireNonNull(address, "address");
        if (address.byteSize() < SOCKADDR_IN_SIZE) {
            throw new IllegalArgumentException("sockaddr must be at least " + SOCKADDR_IN_SIZE + " bytes");
        }
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment capture = Syscalls.newCaptureSegment(arena);
                int rc = (int) CONNECT.invokeExact(capture, fd, address, SOCKADDR_IN_SIZE);
                if (rc >= 0) {
                    return;
                }
                int errno = Syscalls.errno(capture);
                if (errno != Errno.EINTR) {
                    throw new UringException("connect", errno);
                }
            } catch (Throwable failure) {
                throw asRuntime(failure);
            }
        }
    }

    /** Returns the port currently assigned by {@code bind}, including an ephemeral port selected for zero. */
    public static int localPort(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment address = sockaddrIn(arena, 0, 0);
            MemorySegment length = arena.allocate(JAVA_INT);
            length.set(JAVA_INT, 0, SOCKADDR_IN_SIZE);
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            int rc = (int) GETSOCKNAME.invokeExact(capture, fd, address, length);
            requireSuccess("getsockname", rc, capture);
            int returnedLength = length.get(JAVA_INT, 0);
            if (returnedLength < 4) {
                throw new IllegalStateException("getsockname returned only " + returnedLength + " bytes");
            }
            return portOf(address);
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    /** Creates a native 16-byte {@code sockaddr_in}; {@code sin_port} and {@code sin_addr} are network byte order. */
    public static MemorySegment sockaddrIn(Arena arena, int ipv4Address, int port) {
        Objects.requireNonNull(arena, "arena");
        requirePort(port);
        MemorySegment result = arena.allocate(SOCKADDR_IN_SIZE, Integer.BYTES);
        result.set(JAVA_SHORT, 0, (short) AF_INET); // sin_family is native-endian.
        result.set(NETWORK_SHORT, 2, (short) port);
        result.set(NETWORK_INT, 4, ipv4Address);
        return result;
    }

    /** Decodes {@code sin_port} from a native {@code sockaddr_in}. */
    public static int portOf(MemorySegment sockaddrIn) {
        Objects.requireNonNull(sockaddrIn, "sockaddrIn");
        if (sockaddrIn.byteSize() < 4) {
            throw new IllegalArgumentException("sockaddr must expose at least sin_port (4 bytes)");
        }
        return Short.toUnsignedInt(sockaddrIn.get(NETWORK_SHORT, 2));
    }

    /** Sends all {@code byteCount} bytes synchronously, retrying interrupted calls. */
    public static void sendAll(int fd, MemorySegment bytes, long byteCount) {
        requireRange(bytes, 0, byteCount, "send buffer");
        long offset = 0;
        while (offset < byteCount) {
            try {
                long sent = send(fd, bytes.asSlice(offset, byteCount - offset), byteCount - offset, MSG_NOSIGNAL);
                if (sent == 0) {
                    throw new IllegalStateException("send returned zero before all bytes were written");
                }
                offset += sent;
            } catch (UringException failure) {
                if (failure.errno() != Errno.EINTR) {
                    throw failure;
                }
            }
        }
    }

    /** Receives exactly {@code byteCount} bytes synchronously, retrying interrupted calls. */
    public static void receiveExactly(int fd, MemorySegment bytes, long byteCount) {
        requireRange(bytes, 0, byteCount, "receive buffer");
        long offset = 0;
        while (offset < byteCount) {
            try {
                long received = recv(fd, bytes.asSlice(offset, byteCount - offset), byteCount - offset, 0);
                if (received == 0) {
                    throw new IllegalStateException("peer closed before all expected bytes arrived");
                }
                offset += received;
            } catch (UringException failure) {
                if (failure.errno() != Errno.EINTR) {
                    throw failure;
                }
            }
        }
    }

    private static long send(int fd, MemorySegment bytes, long byteCount, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = (long) SEND.invokeExact(capture, fd, bytes, byteCount, flags);
            if (rc < 0) {
                throw new UringException("send", Syscalls.errno(capture));
            }
            return rc;
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    private static long recv(int fd, MemorySegment bytes, long byteCount, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = (long) RECV.invokeExact(capture, fd, bytes, byteCount, flags);
            if (rc < 0) {
                throw new UringException("recv", Syscalls.errno(capture));
            }
            return rc;
        } catch (Throwable failure) {
            throw asRuntime(failure);
        }
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = LINKER.defaultLookup().find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    private static void requireSuccess(String operation, int rc, MemorySegment capture) {
        if (rc < 0) {
            throw new UringException(operation, Syscalls.errno(capture));
        }
    }

    private static void requirePort(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port outside unsigned short range: " + port);
        }
    }

    private static void requireRange(MemorySegment bytes, long offset, long byteCount, String label) {
        Objects.requireNonNull(bytes, label);
        if (offset < 0 || byteCount < 0 || offset > bytes.byteSize() - byteCount) {
            throw new IllegalArgumentException(label + " range offset=" + offset + " length=" + byteCount
                    + " outside segment size=" + bytes.byteSize());
        }
    }

    private static RuntimeException asRuntime(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(failure);
    }
}
