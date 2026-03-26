import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WordApp {

    static final String OUTPUT_FILE    = "words_output.txt";
    static final int    NUM_PROCESSES  = 5;
    static final int    WORDS_PER_PROC = 10;
    static final int    NUM_THREADS    = 4;

    static final String[] WORDS = {
        "яблуко", "банан", "вишня", "дерево", "земля",
        "комп'ютер", "програма", "процес", "потік", "файл",
        "університет", "студент", "викладач", "лекція", "завдання",
        "алгоритм", "структура", "масив", "список", "змінна",
        "клас", "метод", "інтерфейс", "спадкування", "компілятор"
    };


    static void workerMode(int processId) throws Exception {
        long   pid  = ProcessHandle.current().pid();
        Random rnd  = new Random();

        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < WORDS_PER_PROC; i++) {
            chosen.add(WORDS[rnd.nextInt(WORDS.length)]);
        }

        try (RandomAccessFile raf     = new RandomAccessFile(OUTPUT_FILE, "rw");
             var              channel = raf.getChannel();
             var              lock    = channel.lock()) {        

            raf.seek(raf.length());                            
            for (String word : chosen) {
                String line = String.format("[Process #%d | PID=%d] %s%n",
                        processId, pid, word);
                raf.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        System.out.printf("  [Worker #%d | PID=%d] wrote %d words%n",
                processId, pid, WORDS_PER_PROC);
    }


    static void spawnProcesses() throws Exception {
        // Ініціалізуємо файл
        try (var pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(OUTPUT_FILE, false),
                java.nio.charset.StandardCharsets.UTF_8))) {
            pw.printf("=== %d processes, %d words each ===%n%n",
                    NUM_PROCESSES, WORDS_PER_PROC);
        }

        System.out.printf("%n>>> PHASE 1: spawning %d child JVM processes%n", NUM_PROCESSES);

        String javaExe  = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        long start = System.currentTimeMillis();

        // Запускає всі процеси паралельно
        List<Process> procs = new ArrayList<>();
        for (int i = 1; i <= NUM_PROCESSES; i++) {
            Process p = new ProcessBuilder(
                    javaExe,
                    "-cp", classpath,
                    "-Dfile.encoding=UTF-8",
                    "WordApp", "--worker", String.valueOf(i)
            ).redirectErrorStream(true).inheritIO().start();
            procs.add(p);
        }

        for (Process p : procs) p.waitFor();

        System.out.printf("%n    Done in %d ms. File: %s%n",
                System.currentTimeMillis() - start, OUTPUT_FILE);
    }


    static final AtomicInteger totalWords  = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> wordFreq     = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, AtomicInteger> processStats = new ConcurrentHashMap<>();

    static class LineProcessor implements Runnable {
        final int          id;
        final List<String> lines;

        LineProcessor(int id, List<String> lines) {
            this.id    = id;
            this.lines = lines;
        }

        @Override
        public void run() {
            int local = 0;
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("===") || !line.contains("]")) continue;
                String[] parts = line.split("]", 2);
                String word    = parts[1].trim();
                String proc    = parts[0].replace("[", "").trim();

                wordFreq    .computeIfAbsent(word, k -> new AtomicInteger()).incrementAndGet();
                processStats.computeIfAbsent(proc, k -> new AtomicInteger()).incrementAndGet();
                totalWords.incrementAndGet();
                local++;
            }
            System.out.printf("  [Thread #%d] lines=%-3d words=%d%n", id, lines.size(), local);
        }
    }

    static void analyzeFile() throws Exception {
        System.out.printf("%n>>> PHASE 2: analyzing file with %d threads%n", NUM_THREADS);

        List<String> all = Files.readAllLines(Path.of(OUTPUT_FILE),
                java.nio.charset.StandardCharsets.UTF_8);

        ExecutorService pool  = Executors.newFixedThreadPool(NUM_THREADS);
        int             chunk = (int) Math.ceil((double) all.size() / NUM_THREADS);

        for (int i = 0; i < NUM_THREADS; i++) {
            int from = i * chunk;
            int to   = Math.min(from + chunk, all.size());
            if (from >= all.size()) break;
            pool.submit(new LineProcessor(i + 1, all.subList(from, to)));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Результати
        System.out.println("\n+------------------------------------+");
        System.out.println("|          ANALYSIS RESULTS          |");
        System.out.println("+------------------------------------+");
        System.out.printf ("|  Total lines read:  %6d        |%n", all.size());
        System.out.printf ("|  Words found:       %6d        |%n", totalWords.get());
        System.out.printf ("|  Unique words:      %6d        |%n", wordFreq.size());
        System.out.println("+------------------------------------+");

        System.out.println("\nTop-5 most frequent words:");
        wordFreq.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .limit(5)
                .forEach(e -> System.out.printf("  %-24s -> %d%n",
                        e.getKey(), e.getValue().get()));

        System.out.println("\nWords per process:");
        processStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("  %-30s -> %d%n",
                        e.getKey(), e.getValue().get()));
    }

    static void threadLimitInfo() throws Exception {
        Runtime rt    = Runtime.getRuntime();
        int  cpus     = rt.availableProcessors();
        long heapMax  = rt.maxMemory()  / (1024 * 1024);
        long heapFree = rt.freeMemory() / (1024 * 1024);
        long stackKB  = 512;
        long estMax   = (heapFree * 1024) / stackKB;

        System.out.println("\n+----------------------------------------------------+");
        System.out.println("|          HOW MANY THREADS CAN WE CREATE?           |");
        System.out.println("+----------------------------------------------------+");
        System.out.printf ("|  Logical CPU cores:          %4d                 |%n", cpus);
        System.out.printf ("|  Max heap:                %5d MB               |%n", heapMax);
        System.out.printf ("|  Free heap now:           %5d MB               |%n", heapFree);
        System.out.printf ("|  Stack per thread (~):      %3d KB               |%n", stackKB);
        System.out.printf ("|  Estimated platform max: ~%5d               |%n", estMax);
        System.out.println("+----------------------------------------------------+");
        System.out.printf ("|  CPU-bound tasks:   %3d threads  (= cores)        |%n", cpus);
        System.out.printf ("|  I/O-bound tasks:   %3d threads  (cores x 2)      |%n", cpus * 2);
        System.out.printf ("|  Mixed tasks:       %3d threads  (cores x 4)      |%n", cpus * 4);
        System.out.println("+----------------------------------------------------+");

        System.out.println("\n[Test 1] Creating 500 platform threads...");
        CountDownLatch latch = new CountDownLatch(1);
        List<Thread>   list  = new ArrayList<>();
        try {
            for (int i = 0; i < 500; i++) {
                Thread t = new Thread(() -> {
                    try { latch.await(); } catch (InterruptedException ignored) {}
                });
                t.setDaemon(true);
                t.start();
                list.add(t);
            }
            System.out.printf("  [OK] Platform threads created: %d%n", list.size());
        } catch (OutOfMemoryError e) {
            System.out.printf("  [!!] OutOfMemoryError at %d%n", list.size());
        } finally {
            latch.countDown();
        }

        System.out.println("[Test 2] Running 50,000 virtual threads...");
        AtomicInteger done = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50_000; i++) exec.submit(done::incrementAndGet);
        }
        System.out.printf("  [OK] Virtual threads completed: %,d in %d ms%n",
                done.get(), System.currentTimeMillis() - t0);
    }


    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--worker".equals(args[0])) {
            // ── Дочірній процес ──────────────────────────────────────────────
            workerMode(Integer.parseInt(args[1]));
        } else {
            // ── Головний процес ──────────────────────────────────────────────
            System.out.println("====================================================");
            System.out.println("  WordApp  |  multiprocess write + multithread read");
            System.out.println("====================================================");

            spawnProcesses();   // 1. Породжуємо процеси → пишуть у файл
            analyzeFile();      // 2. Читаємо файл у кількох потоках
            threadLimitInfo();  // 3. Демонструємо ліміти потоків

            System.out.println("\n[DONE]");
        }
    }
}
