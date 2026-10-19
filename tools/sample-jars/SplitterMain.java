public class SplitterMain {
    // Outputs one line per segment: "start end"
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SplitterMain <segments>");
            System.exit(1);
            return;
        }

        int segments;
        try {
            segments = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid segments: " + args[0]);
            System.exit(2);
            return;
        }

        if (segments <= 0) {
            System.err.println("Segments must be > 0");
            System.exit(3);
            return;
        }

        long total = 1_000_000; // full range
        long chunk = total / segments;
        long start = 1;

        for (int i = 0; i < segments; i++) {
            long end = (i == segments - 1) ? total : (start + chunk - 1);
            System.out.println(start + " " + end);
            start = end + 1;
        }
    }
}
