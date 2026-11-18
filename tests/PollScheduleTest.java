package com.torquescope;

public class PollScheduleTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        for (int millis : new int[]{5, 100, 600}) {
            PollSchedule schedule = new PollSchedule();
            int speed = 0, rpm = 0;
            long now = 0, speedReady = 0, rpmReady = 0;
            for (int i = 0; i < 40; i++) {
                now += millis; // one completed pedal request
                int pid = schedule.next(now);
                if (pid == 0) continue;
                if (pid == 13) { check(now >= speedReady); speedReady = now + millis + 500; speed++; }
                else { check(pid == 12 && now >= rpmReady); rpmReady = now + millis + 200; rpm++; }
                now += millis;
                schedule.completed(pid, now);
            }
            check(speed > 0 && rpm > 0);
            if (millis == 600) check(speed == 20 && rpm == 20);
            System.out.printf("%d ms, 40 pedal samples: speed=%d, rpm=%d%n", millis, speed, rpm);
        }
    }
}
