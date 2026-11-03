package uring;

/** Executable negative test for the Phase-4 tracer and doctor contracts. */
public final class ObservabilityDemo {

    public static void main(String[] args) {
        RingTracer tracer = new RingTracer(8);
        long first = tracer.submit(0x41, Sqe.OP_READ);
        tracer.complete(first, 0x41, Sqe.OP_READ, 4096, 0);
        if (tracer.snapshot().size() != 1 || !tracer.snapshot().getFirst().completed()) {
            throw new AssertionError("trace did not retain completed read");
        }

        Doctor doctor = new Doctor(16);
        doctor.onSubmit(0x51, Sqe.OP_SEND, 0xA1, 0xB1, true);
        doctor.onSubmit(0x52, Sqe.OP_SEND, 0xA2, 0xB1, true); // injected stream ordering misuse
        doctor.onBufferReturned(0xA1, 0x51); // injected premature managed-buffer return
        doctor.onCompletion(0x51, 3, 0);
        doctor.onCompletion(0x52, 3, 0);

        boolean ordering = doctor.findings().stream()
                .anyMatch(finding -> finding.code() == Doctor.FindingCode.OVERLAPPING_STREAM_SEND);
        boolean lifetime = doctor.findings().stream()
                .anyMatch(finding -> finding.code() == Doctor.FindingCode.BUFFER_RETURNED_BEFORE_TERMINAL_CQE);
        if (!ordering || !lifetime) {
            throw new AssertionError("doctor missed injected ordering/lifetime misuse: " + doctor.findings());
        }

        try (Uring ring = Uring.open(8)) {
            RingTracer integratedTracer = new RingTracer(8);
            Doctor integratedDoctor = new Doctor(8);
            ring.installTracer(integratedTracer);
            ring.installDoctor(integratedDoctor);
            OpToken token = ring.submit(OneShotOp.nop());
            Completion completion = ring.await(token);
            boolean traced = integratedTracer.snapshot().stream()
                    .anyMatch(event -> event.userData() == token.userData()
                            && event.result() == completion.res() && event.completed());
            if (!traced || !integratedDoctor.healthy()) {
                throw new AssertionError("Uring instrumentation did not route typed NOP: trace="
                        + integratedTracer.snapshot() + " doctor=" + integratedDoctor.findings());
            }
        }
        System.out.println("PASS: tracer records real Uring CQE and doctor catches injected misuse");
    }
}
