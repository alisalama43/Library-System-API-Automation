
package api.test.Auth;

import api.endpoints.Routes;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;

public class AuthPerformanceTests {

    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }
    private static final String ADMIN_EMAIL = "admin@library.com";
    private static final String ADMIN_PASSWORD = "Admin@12345";
    static class Sample {
        long responseTime;
        int statusCode;

        Sample(long responseTime, int statusCode) {
            this.responseTime = responseTime;
            this.statusCode = statusCode;
        }
    }
    private Response loginRequest(String email, String password) {

        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);

        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(body)
                .when()
                .post(Routes.LOGIN);
    }


    private Response registerRequest(String email) {

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Performance User");
        body.put("email", email);
        body.put("password", "secret123");

        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(body)
                .when()
                .post(Routes.REGISTER);
    }

    private void runLoadTest(
            String testName,
            int numberOfUsers,
            boolean registerTest) throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfUsers);

        CountDownLatch ready = new CountDownLatch(numberOfUsers);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Sample>> futures = new ArrayList<>();

        long testStart = System.nanoTime();

        for (int i = 0; i < numberOfUsers; i++) {

            final int userNumber = i;

            Callable<Sample> task = () -> {

                ready.countDown();
                start.await();

                long requestStart = System.nanoTime();

                try {
                    Response response;

                    if (registerTest) {

                        String email = "load_user_"
                                + System.currentTimeMillis()
                                + "_" + userNumber
                                + "@test.com";

                        response = registerRequest(email);

                    } else {

                        response = loginRequest(
                                ADMIN_EMAIL,
                                ADMIN_PASSWORD
                        );
                    }

                    long requestEnd = System.nanoTime();

                    long responseTime =
                            TimeUnit.NANOSECONDS.toMillis(
                                    requestEnd - requestStart
                            );

                    return new Sample(
                            responseTime,
                            response.statusCode()
                    );

                } catch (Exception e) {

                    return new Sample(-1, -1);
                }
            };

            futures.add(executor.submit(task));
        }

        ready.await();

        testStart = System.nanoTime();
        start.countDown();

        List<Long> responseTimes = new ArrayList<>();

        int successfulRequests = 0;
        int failedRequests = 0;

        for (Future<Sample> future : futures) {

            Sample sample = future.get();

            if (sample.statusCode >= 200 &&
                    sample.statusCode < 300) {

                successfulRequests++;

            } else {

                failedRequests++;
            }

            if (sample.responseTime >= 0) {
                responseTimes.add(sample.responseTime);
            }
        }

        long testEnd = System.nanoTime();

        executor.shutdown();

        double totalSeconds =
                (testEnd - testStart) / 1_000_000_000.0;

        double averageResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        long maxResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

        double throughput =
                numberOfUsers / totalSeconds;

        System.out.println("\n========== " + testName + " ==========");

        System.out.println("Total Requests: " + numberOfUsers);
        System.out.println("Successful: " + successfulRequests);
        System.out.println("Failed: " + failedRequests);

        System.out.println("Total Time: "
                + String.format("%.2f", totalSeconds) + " seconds");

        System.out.println("Average Response Time: "
                + String.format("%.2f", averageResponseTime) + " ms");

        System.out.println("Max Response Time: "
                + maxResponseTime + " ms");

        System.out.println("Throughput: "
                + String.format("%.2f", throughput) + " Requests/sec");

        System.out.println("====================================\n");
    }

    @Test
    public void loginLoadTest() throws Exception {

        runLoadTest("Login Load Test", 100, false);
    }

    @Test
    public void registerLoadTest() throws Exception {

        runLoadTest("Register Load Test", 100, true);
    }

    @Test
    public void concurrentLoginTest() throws Exception {

        runLoadTest("Concurrent Login", 50, false);
    }

    @Test
    public void authenticationStressTest() throws Exception {

        int[] userLevels = {10, 25, 50, 100};

        for (int users : userLevels) {

            runLoadTest(
                    "Stress Test - " + users + " Users",
                    users,
                    false
            );
        }
    }

    @Test
    public void responseTimeTest() throws Exception {

        runLoadTest("Response Time Test", 20, false);
    }

    @Test
    public void throughputTest() throws Exception {

        runLoadTest("Throughput Test", 100, false);
    }
    @Test
    public void concurrentRegistrationSameEmailTest()
            throws Exception {

        int numberOfRequests = 10;

        String email = "race_" +
                UUID.randomUUID() +
                "@example.com";

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready =
                new CountDownLatch(numberOfRequests);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<Integer>> results = new ArrayList<>();

        try {
            for (int i = 0; i < numberOfRequests; i++) {

                Future<Integer> result = executor.submit(() -> {

                    ready.countDown();

                    start.await();

                    String body = """
                        {
                          "fullName": "Race Test User",
                          "email": "%s",
                          "password": "Test@12345"
                        }
                        """.formatted(email);

                    Response response = given()
                            .contentType("application/json")
                            .body(body)
                            .when()
                            .post(Routes.REGISTER);

                    return response.statusCode();
                });

                results.add(result);
            }

            // Wait until all threads are ready
            Assert.assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Not all requests became ready"
            );

            // Release all requests together
            start.countDown();

            int successCount = 0;
            int conflictCount = 0;

            for (Future<Integer> result : results) {

                int statusCode =
                        result.get(30, TimeUnit.SECONDS);

                System.out.println(
                        "Registration Status: " + statusCode
                );

                if (statusCode == 200 || statusCode == 201) {
                    successCount++;
                } else if (statusCode == 409) {
                    conflictCount++;
                }
            }

            System.out.println("Success: " + successCount);
            System.out.println("Conflict: " + conflictCount);

            Assert.assertEquals(
                    successCount, 1,
                    "Only one registration should succeed"
            );

            Assert.assertEquals(
                    conflictCount, numberOfRequests - 1,
                    "Other registrations should return 409"
            );

        } finally {
            executor.shutdownNow();
        }
    }
}
