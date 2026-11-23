package api.test.books;

import api.endpoints.BookEndPointes;
import api.endpoints.InventoryEndpoints;
import api.endpoints.Routes;
import api.payloads.BookRequest;
import api.payloads.InventoryRequest;
import api.payloads.LoginRequest;
import api.test.Auth.BaseTest;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;

public class PerformanceAndConcurrencyTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final long MAX_SLA_MS = 3000L; // الحد الأقصى المقبول للاستجابة (3 ثوانٍ)

    @Test(priority = 1)
    public void testCriticalEndpointsMeetResponseTimeSla() {
        Response booksRes = BookEndPointes.getAllBooks();
        long booksTime = booksRes.getTime();
        System.out.println("GET /api/books Response Time: " + booksTime + " ms");
        Assert.assertEquals(booksRes.statusCode(), 200);
        Assert.assertTrue(booksTime < MAX_SLA_MS, "Catalog endpoint exceeded SLA: " + booksTime + " ms");
        long startLogin = System.currentTimeMillis();
        Response loginRes = given()
                .contentType(ContentType.JSON)
                .body(new LoginRequest("admin@library.com", "Admin@12345"))
                .post(Routes.LOGIN);
        long loginTime = System.currentTimeMillis() - startLogin;

        System.out.println("POST /api/auth/login Response Time: " + loginTime + " ms");
        Assert.assertEquals(loginRes.statusCode(), 200);
        Assert.assertTrue(loginTime < MAX_SLA_MS, "Login endpoint exceeded SLA: " + loginTime + " ms");
    }


    @Test(priority = 2)
    public void testConcurrentCatalogBrowsingLoad() throws InterruptedException, ExecutionException {
        int concurrentUsers = 15;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch startGate = new CountDownLatch(1);

        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentUsers; i++) {
            tasks.add(() -> {
                startGate.await();
                return BookEndPointes.getAllBooks();
            });
        }

        List<Future<Response>> futures = new ArrayList<>();
        for (Callable<Response> task : tasks) {
            futures.add(executor.submit(task));
        }

        startGate.countDown();

        long totalTime = 0;
        long maxTime = 0;
        int successCount = 0;

        for (Future<Response> future : futures) {
            Response res = future.get();
            if (res.statusCode() == 200) {
                successCount++;
            }
            long time = res.getTime();
            totalTime += time;
            if (time > maxTime) maxTime = time;
        }

        executor.shutdown();

        double avgTime = (double) totalTime / concurrentUsers;
        System.out.printf("Load Test Results (%d concurrent users) -> Success: %d/%d | Avg: %.2f ms | Max: %d ms%n",
                concurrentUsers, successCount, concurrentUsers, avgTime, maxTime);

        Assert.assertEquals(successCount, concurrentUsers, "Some concurrent requests failed under load!");
        Assert.assertTrue(avgTime < MAX_SLA_MS, "Average response time under load exceeded SLA!");
    }


    @Test(priority = 3)
    public void testRaceConditionPreventNegativeInventoryOnConcurrentBorrows() throws InterruptedException {

        BookRequest book = new BookRequest(
                "Limited Edition Book",
                "Rare Author",
                2024,
                null,
                null,
                null,
                200.0,
                null
        );
        int bookId = BookEndPointes.createBook(book, adminToken).jsonPath().getInt("id");
        int inventoryId = InventoryEndpoints.createInventory(new InventoryRequest(bookId, 1), adminToken)
                .jsonPath().getInt("id");

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    Response res = given()
                            .header("Authorization", "Bearer " + clientToken)
                            .contentType(ContentType.JSON)
                            .body(Map.of("bookId", bookId))
                            .post(Routes.CREATE_BORROW);
                    statusCodes.add(res.statusCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        long successfulBorrows = statusCodes.stream().filter(code -> code == 200 || code == 201).count();
        System.out.println("Concurrent Borrow Status Codes: " + statusCodes);

        Assert.assertTrue(
                successfulBorrows <= 1,
                "RACE CONDITION DETECTED: Multiple borrows succeeded for a single available copy!"
        );

        Response invCheck = InventoryEndpoints.getInventoryByBookId(bookId);
        if (invCheck.statusCode() == 200) {
            int availableCopies = invCheck.jsonPath().getInt("availableCopies");
            Assert.assertTrue(availableCopies >= 0, "CRITICAL: Available copies dropped below zero: " + availableCopies);
        }

        InventoryEndpoints.deleteInventory(inventoryId, adminToken);
        BookEndPointes.deleteBook(bookId, adminToken);
    }
}