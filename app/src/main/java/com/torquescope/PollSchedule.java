package com.torquescope;

/** One secondary request per pedal sample. Oldest deadline wins on slow links. */
final class PollSchedule {
    private long speedDue, rpmDue;

    int next(long now) {
        if (speedDue <= now && speedDue <= rpmDue) return 0x0d;
        if (rpmDue <= now) return 0x0c;
        return 0;
    }

    void completed(int pid, long now) {
        if (pid == 0x0d) speedDue = now + 500;
        else if (pid == 0x0c) rpmDue = now + 200;
    }
}
