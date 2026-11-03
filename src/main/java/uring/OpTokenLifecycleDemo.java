package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Self-checking Phase 1 demo for typed one-shot/multishot token lifetime.
 * It uses a real managed READ_FIXED, then a synthetic F_MORE/final sequence
 * to test the token state machine before network multishot operations exist.
 */
public final class OpTokenLifecycleDemo {

    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        byte[] sample = "OpToken borrows managed buffers until terminal CQE\n".repeat(48).getBytes();
        Path path = Files.createTempFile("uring-panama-token-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(8);
             RegisteredBufferArena arena = ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            try {
                realOneShotRead(ring, arena, fd, sample);
                failedPreparationRollsBackBorrow(ring, arena, fd);
                multishotTerminalBoundary(arena);
            } finally {
                FileOps.close(fd);
            }
        } finally {
            Files.deleteIfExists(path);
        }

        System.out.println("PASS: OpToken typed lifetime invariants hold");
    }

    private static void realOneShotRead(Uring ring, RegisteredBufferArena arena, int fd, byte[] expected) {
        RegisteredBufferArena.Buffer buffer = arena.buffer(0);
        OpToken token = ring.submit(OneShotOp.readFixed(arena, fd, buffer, expected.length, 0));
        if (token.kind() != OpToken.Kind.ONE_SHOT || token.isTerminal() || !buffer.isBorrowed()) {
            throw new AssertionError("typed READ_FIXED did not enter the expected borrowed nonterminal state");
        }
        expectIllegalState(buffer::segment, "managed buffer must not be reused while token is live");
        expectIllegalState(() -> ring.submit(OneShotOp.readFixed(arena, fd, buffer, expected.length, 0)),
                "a second token must not borrow the same buffer concurrently");

        Completion completion = ring.await(token);
        if (!token.isTerminal() || token.completionCount() != 1 || completion.res() != expected.length
                || completion.more() || token.lifecycleFailure() != null) {
            throw new AssertionError("unexpected typed READ_FIXED completion: " + completion);
        }
        Completion queued = token.awaitNext();
        if (!completion.equals(queued) || token.awaitNext() != null) {
            throw new AssertionError("token did not retain exactly its one completion");
        }
        if (buffer.isBorrowed()) {
            throw new AssertionError("terminal CQE did not release the managed buffer");
        }

        byte[] actual = new byte[expected.length];
        MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError("typed READ_FIXED content mismatch");
        }
        System.out.println("PASS: typed one-shot READ_FIXED borrows until terminal byte-exact CQE");
    }

    private static void failedPreparationRollsBackBorrow(Uring ring, RegisteredBufferArena arena, int fd) {
        RegisteredBufferArena.Buffer buffer = arena.buffer(0);
        expectIllegalArgument(() -> ring.submit(OneShotOp.readFixed(arena, fd, buffer, BUFFER_SIZE + 1, 0)),
                "invalid typed len must fail before SQ-tail publication");
        if (buffer.isBorrowed()) {
            throw new AssertionError("failed typed preparation leaked its buffer borrow");
        }
        Completion nop = ring.await(ring.submit(OneShotOp.nop()));
        if (nop.res() != 0) {
            throw new AssertionError("typed NOP after preparation rollback failed: " + nop);
        }
        System.out.println("PASS: failed typed preparation rolls back borrow and pending SQE reservation");
    }

    private static void multishotTerminalBoundary(RegisteredBufferArena arena) {
        RegisteredBufferArena.Buffer buffer = arena.buffer(0);
        OpToken token = new OpToken(0x7F00L, OpToken.Kind.MULTI_SHOT, buffer);
        token.onCompletion(new Completion(token.userData(), 32, Cqe.FLAG_MORE));
        if (token.isTerminal() || !buffer.isBorrowed() || token.completionCount() != 1) {
            throw new AssertionError("F_MORE completion released a multishot buffer too early");
        }
        token.onCompletion(new Completion(token.userData(), 0, 0));
        if (!token.isTerminal() || buffer.isBorrowed() || token.completionCount() != 2) {
            throw new AssertionError("terminal multishot CQE did not release its buffer");
        }
        Completion first = token.awaitNext();
        Completion second = token.awaitNext();
        if (first == null || !first.more() || second == null || second.more() || token.awaitNext() != null) {
            throw new AssertionError("multishot token completion queue lost ordering or terminal state");
        }
        System.out.println("PASS: multishot token retains buffer through F_MORE and releases at terminal CQE");
    }

    private static void expectIllegalState(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException: " + message);
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException: " + message);
    }
}
