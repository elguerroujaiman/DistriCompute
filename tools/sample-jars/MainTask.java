public class MainTask {
    // Simulates ~30s total runtime for full range.
    public static void main(String[] args) {
        long start = 1;
        long end = 1_000_000;

        if (args.length >= 2) {
            try {
                start = Long.parseLong(args[0]);
                end = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid args. Expected: <start> <end>");
                System.exit(2);
                return;
            }
        }

        if (end < start) {
            System.err.println("Invalid range: end < start");
            System.exit(3);
            return;
        }

        long totalRange = 1_000_000;
        long range = (end - start + 1);
        double ratio = Math.min(1.0, Math.max(0.0, (double) range / (double) totalRange));
        long sleepMs = Math.round(30_000 * ratio);

        long sum = 0;
        for (long i = start; i <= end; i++) {
            sum += i;
        }

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("SUM(" + start + ".." + end + ") = " + sum);
        System.out.println("Simulated work time: ~" + (sleepMs / 1000.0) + "s");
    }
}
