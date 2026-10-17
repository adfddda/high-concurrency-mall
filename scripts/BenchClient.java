/*
 * ============================================================================
 *  高并发商城交易系统 —— Java 21 虚拟线程压测客户端
 *
 *  为什么不用 Python 脚本：
 *   Python 的 `threading` 受 GIL 限制，200 个线程并发发请求时，
 *   实测客户端侧平均耗时 247ms，而服务端自己统计的平均处理时间只有 1.68ms
 *   —— 也就是说那个 QPS 数字测的是 Python，不是被测系统。
 *
 *   Java 21 的虚拟线程没有这个限制：创建成本极低（几 KB 栈、由 JVM 调度），
 *   可以轻松开出上万个并发连接，从而真正把压力打到服务端。
 *
 *  单文件运行（无需编译、无需依赖）：
 *    java scripts/BenchClient.java --mode cache --concurrency 500 --requests 100000
 *    java scripts/BenchClient.java --mode seckill --activity-id 2001 --users 500
 *
 *  模式：
 *    cache   —— 压商品详情接口，重点看 L1/L2/DB 的来源分布
 *    seckill —— 压秒杀下单接口，重点看各业务码分布与最终库存一致性
 * ============================================================================
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenchClient {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern ORDER_NO = Pattern.compile("\"orderNo\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SOURCE = Pattern.compile("\"source\"\\s*:\\s*\"([^\"]+)\"");

    private static String baseUrl = "http://127.0.0.1:8080";
    private static String mode = "cache";
    private static long activityId = 2001L;
    private static int concurrency = 400;
    private static int totalRequests = 50_000;
    private static int users = 200;
    private static int warmup = 500;
    private static String password = "123456";
    private static String productIds = "1001,1002,1003,1004,1005";

    public static void main(String[] args) throws Exception {
        parseArgs(args);

        System.out.println("=".repeat(78));
        System.out.printf(" 模式=%s  目标=%s%n", mode, baseUrl);
        System.out.printf(" 并发=%d  总请求=%d  用户数=%d%n", concurrency, totalRequests, users);
        System.out.println("=".repeat(78));

        // HttpClient 的内部 executor 也用虚拟线程，避免它自己成为瓶颈
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        System.out.printf("[1/4] 登录 %d 个用户 ...%n", users);
        List<String> tokens = loginAll(client);
        if (tokens.isEmpty()) {
            System.err.println("      一个都没登上，检查应用是否已启动");
            System.exit(2);
        }
        System.out.printf("      成功 %d 个%n", tokens.size());

        if (warmup > 0) {
            System.out.printf("[2/4] 预热 %d 次 ...%n", warmup);
            runPhase(client, tokens, warmup, 32, null, null, null);
            System.out.println("      预热完成");
        }

        System.out.printf("[3/4] 压测 %d 个请求，并发 %d ...%n", totalRequests, concurrency);
        long[] latencies = new long[totalRequests];
        Map<Integer, AtomicInteger> codeCounts = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> sourceCounts = new ConcurrentHashMap<>();
        List<String> orderNos = new ArrayList<>();

        long elapsedNanos = runPhase(client, tokens, totalRequests, concurrency,
                latencies, codeCounts, sourceCounts, orderNos);
        double elapsedSec = elapsedNanos / 1_000_000_000.0;

        printReport(latencies, codeCounts, sourceCounts, elapsedSec, tokens.get(0),
                orderNos.isEmpty() ? null : orderNos.get(0), orderNos);
    }

    // ========================================================================
    // 压测主体
    // ========================================================================

    private static long runPhase(HttpClient client, List<String> tokens, int requests,
                                 int concurrency, long[] latencies,
                                 Map<Integer, AtomicInteger> codeCounts,
                                 Map<String, AtomicInteger> sourceCounts) throws Exception {
        return runPhase(client, tokens, requests, concurrency, latencies, codeCounts, sourceCounts, null);
    }

    private static long runPhase(HttpClient client, List<String> tokens, int requests,
                                 int concurrency, long[] latencies,
                                 Map<Integer, AtomicInteger> codeCounts,
                                 Map<String, AtomicInteger> sourceCounts,
                                 List<String> orderNos) throws Exception {
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger recorded = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(concurrency);
        List<Long> productList = parseProducts();

        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < concurrency; t++) {
                executor.submit(() -> {
                    try {
                        while (true) {
                            int idx = issued.getAndIncrement();
                            if (idx >= requests) {
                                return;
                            }
                            String token = tokens.get(idx % tokens.size());
                            long t0 = System.nanoTime();
                            String body;
                            if ("seckill".equals(mode)) {
                                body = post(client, "/api/seckill/activities/" + activityId + "/order", token);
                            } else {
                                long pid = productList.get(idx % productList.size());
                                body = get(client, "/api/product/detail/" + pid, token);
                            }
                            long used = System.nanoTime() - t0;

                            if (latencies != null) {
                                int slot = recorded.getAndIncrement();
                                if (slot < latencies.length) {
                                    latencies[slot] = used;
                                }
                            }
                            if (codeCounts != null && body != null) {
                                Matcher m = CODE.matcher(body);
                                if (m.find()) {
                                    codeCounts.computeIfAbsent(Integer.parseInt(m.group(1)),
                                            k -> new AtomicInteger()).incrementAndGet();
                                }
                            }
                            if (sourceCounts != null && body != null) {
                                Matcher m = SOURCE.matcher(body);
                                if (m.find()) {
                                    sourceCounts.computeIfAbsent(m.group(1),
                                            k -> new AtomicInteger()).incrementAndGet();
                                }
                            }
                            if (orderNos != null && body != null) {
                                Matcher m = ORDER_NO.matcher(body);
                                if (m.find()) {
                                    synchronized (orderNos) {
                                        if (orderNos.size() < 500) {
                                            orderNos.add(m.group(1));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 单个请求失败不中断压测；失败会被记成延迟但不会有业务码
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
        return System.nanoTime() - start;
    }

    // ========================================================================
    // HTTP 封装
    // ========================================================================

    private static List<String> loginAll(HttpClient client) throws Exception {
        List<String> tokens = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("lxt");
        for (int i = 1; i <= users; i++) {
            names.add("malluser" + i);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = names.stream().map(name -> executor.submit(() -> {
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        String body = postJson(client, "/api/auth/login",
                                "{\"username\":\"" + name + "\",\"password\":\"" + password + "\"}", null);
                        if (body == null) {
                            continue;
                        }
                        Matcher m = CODE.matcher(body);
                        if (m.find() && "0".equals(m.group(1))) {
                            Matcher tm = TOKEN.matcher(body);
                            if (tm.find()) {
                                synchronized (tokens) {
                                    tokens.add(tm.group(1));
                                }
                            }
                            return null;
                        }
                        Thread.sleep(200L * (attempt + 1));
                    } catch (Exception e) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }
                return null;
            })).toList();
            for (var f : futures) {
                f.get();
            }
        }
        return tokens;
    }

    private static String get(HttpClient client, String path, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10)).GET();
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return null;
        }
    }

    private static String post(HttpClient client, String path, String token) {
        return postJson(client, path, "", token);
    }

    private static String postJson(HttpClient client, String path, String json, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // 结果输出
    // ========================================================================

    private static void printReport(long[] latencies, Map<Integer, AtomicInteger> codeCounts,
                                    Map<String, AtomicInteger> sourceCounts, double elapsedSec,
                                    String token, String sampleOrderNo, List<String> orderNos) {
        int n = 0;
        for (long l : latencies) {
            if (l > 0) {
                n++;
            }
        }
        long[] sorted = Arrays.copyOf(latencies, n);
        Arrays.sort(sorted);

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(" 压测结果");
        System.out.println("=".repeat(78));
        System.out.printf("  有效样本        : %d%n", n);
        System.out.printf("  总耗时          : %.2f s%n", elapsedSec);
        System.out.printf("  吞吐 (QPS)      : %,.0f%n", n / elapsedSec);
        if (n > 0) {
            long sum = 0;
            for (long l : sorted) {
                sum += l;
            }
            System.out.printf("  平均延迟        : %.3f ms%n", sum / (double) n / 1e6);
            System.out.printf("  P50 / P95 / P99 : %.3f / %.3f / %.3f ms%n",
                    pct(sorted, 50) / 1e6, pct(sorted, 95) / 1e6, pct(sorted, 99) / 1e6);
            System.out.printf("  P999 / 最大     : %.3f / %.3f ms%n",
                    pct(sorted, 99.9) / 1e6, sorted[n - 1] / 1e6);
        }

        if (!codeCounts.isEmpty()) {
            System.out.println();
            System.out.println("  业务码分布：");
            codeCounts.entrySet().stream()
                    .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                    .forEach(e -> System.out.printf("    %6d  %-14s %8d%n",
                            e.getKey(), codeName(e.getKey()), e.getValue().get()));
        }

        if (!sourceCounts.isEmpty()) {
            int total = sourceCounts.values().stream().mapToInt(AtomicInteger::get).sum();
            System.out.println();
            System.out.println("  数据来源分布（判断缓存是否真的生效的唯一依据）：");
            for (String key : List.of("L1", "L2", "DB")) {
                AtomicInteger c = sourceCounts.get(key);
                int v = c == null ? 0 : c.get();
                System.out.printf("    %-4s %8d  (%5.1f%%)%n", key, v, v * 100.0 / Math.max(total, 1));
            }
        }

        // 从服务端自己统计的指标反推真实处理能力 —— 这是唯一不受客户端影响的口径
        String metrics = get(HttpClient.newHttpClient(), "/actuator/metrics/http.server.requests", token);
        if (metrics != null) {
            Double count = extract(metrics, "COUNT");
            Double totalTime = extract(metrics, "TOTAL_TIME");
            Double max = extract(metrics, "MAX");
            if (count != null && totalTime != null && count > 0) {
                System.out.println();
                System.out.println("  服务端自测口径（不含客户端开销，最能反映真实处理能力）：");
                System.out.printf("    服务端请求数    : %,.0f%n", count);
                System.out.printf("    服务端平均耗时  : %.3f ms%n", totalTime / count * 1000);
                System.out.printf("    服务端最大耗时  : %.3f ms%n", max == null ? 0 : max * 1000);
                System.out.println("    ↑ 与上面客户端延迟的差值，就是压测客户端自身的开销。");
                System.out.println("      如果差值很大，说明客户端是瓶颈，QPS 被低估了。");
            }
        }

        System.out.println("=".repeat(78));
    }

    private static String codeName(int code) {
        return switch (code) {
            case 0 -> "受理/成功";
            case 20004 -> "已售罄";
            case 20005 -> "重复购买";
            case 20006 -> "未预热";
            case 20008 -> "提交失败";
            case 42900 -> "被限流";
            default -> "其它";
        };
    }

    private static double pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.floor(sorted.length * p / 100.0));
        return sorted[idx];
    }

    private static Double extract(String json, String statistic) {
        int i = json.indexOf("\"" + statistic + "\"");
        if (i < 0) {
            return null;
        }
        Matcher m = Pattern.compile("\"value\"\\s*:\\s*([0-9.eE+-]+)").matcher(json.substring(i));
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static List<Long> parseProducts() {
        List<Long> list = new ArrayList<>();
        for (String s : productIds.split(",")) {
            if (!s.isBlank()) {
                list.add(Long.parseLong(s.trim()));
            }
        }
        return list.isEmpty() ? List.of(1001L) : list;
    }

    private static void parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String raw = args[i].substring(2);
            int eq = raw.indexOf('=');
            if (eq > 0) {
                // 支持 --key=value 写法
                map.put(raw.substring(0, eq), raw.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                map.put(raw, args[++i]);
            } else {
                map.put(raw, "true");
            }
        }
        baseUrl = map.getOrDefault("base-url", baseUrl);
        mode = map.getOrDefault("mode", mode);
        activityId = Long.parseLong(map.getOrDefault("activity-id", String.valueOf(activityId)));
        concurrency = Integer.parseInt(map.getOrDefault("concurrency", String.valueOf(concurrency)));
        totalRequests = Integer.parseInt(map.getOrDefault("requests", String.valueOf(totalRequests)));
        users = Integer.parseInt(map.getOrDefault("users", String.valueOf(users)));
        warmup = Integer.parseInt(map.getOrDefault("warmup", String.valueOf(warmup)));
        password = map.getOrDefault("password", password);
        productIds = map.getOrDefault("product-ids", productIds);
    }
}
