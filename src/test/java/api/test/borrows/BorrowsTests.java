package api.test.borrows;

import api.endpoints.BookEndPointes;
import api.endpoints.Routes;
import api.payloads.BookRequest;
import api.test.Auth.BaseTest;
import api.utilites.RestAssuredLogFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class BorrowsTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private static final String BORROWS_URL =
            Routes.BASE_URL + "/api/borrows";

    private static final String BORROW_BY_ID_URL =
            Routes.BASE_URL + "/api/borrows/{id}";

    private static final String RETURN_BORROW_URL =
            Routes.BASE_URL + "/api/borrows/{id}/return";

    private static final String MY_BORROWS_URL =
            Routes.BASE_URL + "/api/borrows/my";

    private static final String INVENTORY_URL =
            Routes.BASE_URL + "/api/inventory";

    private static final String INVENTORY_BY_ID_URL =
            Routes.BASE_URL + "/api/inventory/{id}";

    private static final String INVENTORY_BY_BOOK_ID_URL =
            Routes.BASE_URL + "/api/inventory/book/{bookId}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<Integer> createdInventoryIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    private final List<Integer> createdBookIds =
            Collections.synchronizedList(new ArrayList<Integer>());

    // Tracks [borrowId, ownerToken] so active borrows can be returned cleanly in @AfterClass
    private final List<String[]> activeBorrowsToCleanup =
            Collections.synchronizedList(new ArrayList<String[]>());


    // ==================== Helpers ====================

    private String randomLetters(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }

        return sb.toString();
    }

    private int createTempBook() {
        BookRequest bookPayload = new BookRequest(
                "Borrow Book " + randomLetters(6),
                "Borrow Author " + randomLetters(5),
                2025,
                "Library Press",
                "Fiction",
                null,
                75.0,
                "Temporary book created for borrow testing"
        );

        Response bookRes = BookEndPointes.createBook(bookPayload, adminToken);
        Assert.assertEquals(
                bookRes.statusCode(),
                201,
                "Failed to create helper book: " + bookRes.asString()
        );

        int bookId = bookRes.jsonPath().getInt("id");
        createdBookIds.add(bookId);
        return bookId;
    }

    private int[] createBookWithInventory(int availableCopies) {
        int bookId = createTempBook();

        Response invRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId, "availableCopies", availableCopies))
                .post(INVENTORY_URL);

        Assert.assertEquals(
                invRes.statusCode(),
                201,
                "Failed to create inventory for book " + bookId + ": " + invRes.asString()
        );

        int invId = invRes.jsonPath().getInt("id");
        createdInventoryIds.add(invId);
        return new int[]{bookId, invId};
    }

    private int getAvailableCopiesByBookId(int bookId) {
        return given()
                .pathParam("bookId", bookId)
                .get(INVENTORY_BY_BOOK_ID_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("availableCopies");
    }

    private String registerFreshClientToken() {
        String email = "borrower_" + randomLetters(8) + "@example.com";
        String password = "Password@123";
        String fullName = "Client " + randomLetters(6) + " " + randomLetters(6);

        Response regRes = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "fullName", fullName,
                        "email", email,
                        "password", password
                ))
                .post(Routes.BASE_URL + "/api/auth/register");

        Assert.assertTrue(
                regRes.statusCode() == 200 || regRes.statusCode() == 201,
                "Failed to register isolated client: " + regRes.asString()
        );

        String token = regRes.jsonPath().getString("token");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        Response loginRes = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .post(Routes.BASE_URL + "/api/auth/login");

        Assert.assertEquals(loginRes.statusCode(), 200, "Failed to login isolated client: " + loginRes.asString());
        token = loginRes.jsonPath().getString("token");
        if (token == null || token.isEmpty()) {
            token = loginRes.jsonPath().getString("accessToken");
        }
        Assert.assertNotNull(token, "Token was null after login");
        return token;
    }


    // ==================== 1. JSON Data Loader ====================

    private JsonNode loadTestData() {
        try {
            InputStream inputStream = getClass()
                    .getClassLoader()
                    .getResourceAsStream("testdata/borrowsdata.json");

            if (inputStream == null) {
                inputStream = getClass()
                        .getClassLoader()
                        .getResourceAsStream("borrowsdata.json");
            }

            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    return mapper.readTree(is);
                }
            }

            File fallbackFile =
                    new File("src/test/resources/testdata/borrowsdata.json");

            if (!fallbackFile.exists()) {
                fallbackFile =
                        new File("src/test/resources/borrowsdata.json");
            }

            if (!fallbackFile.exists()) {
                throw new IOException(
                        "borrowsdata.json was not found in test resources"
                );
            }

            return mapper.readTree(fallbackFile);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load borrowsdata.json",
                    e
            );
        }
    }


    // ==================== 2. Data Providers ====================

    private Object[][] extractCases(String sectionName) {
        JsonNode casesNode = loadTestData().get(sectionName);

        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalStateException(
                    "No test cases found for section: " + sectionName
            );
        }

        Object[][] data = new Object[casesNode.size()][1];

        for (int i = 0; i < casesNode.size(); i++) {
            data[i][0] = casesNode.get(i);
        }

        return data;
    }

    @DataProvider(name = "createValidBorrowData")
    public Object[][] createValidBorrowData() {
        return extractCases("createValidCases");
    }

    @DataProvider(name = "createInvalidBorrowData")
    public Object[][] createInvalidBorrowData() {
        return extractCases("createInvalidCases");
    }


    // ==================== 3. Smoke & RBAC Access Tests ====================

    @Test(priority = 1, groups = {"smoke", "regression"})
    public void testSmokeEndpointsAndAdminOnlyAllBorrowsRBAC() {
        // 1. Unauthenticated access must be rejected (401/403)
        int unauthMyStatus = given().get(MY_BORROWS_URL).statusCode();
        Assert.assertTrue(unauthMyStatus == 401 || unauthMyStatus == 403);

        int unauthAllStatus = given().get(BORROWS_URL).statusCode();
        Assert.assertTrue(unauthAllStatus == 401 || unauthAllStatus == 403);

        // 2. Signed-in Client can read own borrow history -> 200
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(MY_BORROWS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));

        // 3. Signed-in Client MUST NOT view all members' borrow records -> 403
        given()
                .header("Authorization", "Bearer " + clientToken)
                .when()
                .get(BORROWS_URL)
                .then()
                .statusCode(403);

        // 4. Admin can view all borrow records -> 200
        given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(BORROWS_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", isA(List.class));
    }


    // ==================== 4. Full Lifecycle, 14-Day Rule & Stock Sync ====================

    @Test(priority = 2, groups = {"smoke", "regression"})
    public void testCompleteBorrowReturnLifecycleStockSyncAndLoanPeriod() {
        String userToken = registerFreshClientToken();
        int[] bookAndInv = createBookWithInventory(4);
        int bookId = bookAndInv[0];

        // 1. BORROW BOOK (POST /api/borrows) -> 201
        Response borrowRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);

        Assert.assertEquals(
                borrowRes.statusCode(),
                201,
                "Borrow failed: " + borrowRes.asString()
        );

        int borrowId = borrowRes.jsonPath().getInt("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        String borrowDateStr = borrowRes.jsonPath().getString("borrowDate");
        String dueDateStr = borrowRes.jsonPath().getString("dueDate");

        Assert.assertTrue(borrowId > 0);
        Assert.assertEquals(borrowRes.jsonPath().getInt("book.id"), bookId);
        Assert.assertEquals(borrowRes.jsonPath().getString("status"), "ACTIVE");
        Assert.assertNull(borrowRes.jsonPath().get("returnDate"));
        Assert.assertEquals(borrowRes.jsonPath().getFloat("fineAmount"), 0.0f, 0.01f);

        // Verify Business Rule: Loan period is strictly borrowDate + 14 days
        LocalDate borrowDate = LocalDate.parse(borrowDateStr);
        LocalDate expectedDueDate = borrowDate.plusDays(14);
        Assert.assertEquals(
                dueDateStr,
                expectedDueDate.toString(),
                "Loan period rule violated: dueDate must be borrowDate + 14 days"
        );

        // Verify Inventory decremented from 4 -> 3
        Assert.assertEquals(
                getAvailableCopiesByBookId(bookId),
                3,
                "Inventory availableCopies did not decrement after borrow!"
        );

        // 2. GET SINGLE BORROW BY ID (GET /api/borrows/{id}) -> 200
        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .when()
                .get(BORROW_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(borrowId))
                .body("status", equalTo("ACTIVE"))
                .body("returnDate", nullValue());

        // 3. GET MY BORROWS (GET /api/borrows/my) includes this record -> 200
        List<Integer> myBorrowIds = given()
                .header("Authorization", "Bearer " + userToken)
                .when()
                .get(MY_BORROWS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Assert.assertTrue(myBorrowIds.contains(borrowId), "Borrow record missing from /api/borrows/my");

        // 4. RETURN BOOK (PUT /api/borrows/{id}/return) -> 200
        Response returnRes = given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL);

        Assert.assertEquals(
                returnRes.statusCode(),
                200,
                "Return failed: " + returnRes.asString()
        );
        Assert.assertEquals(returnRes.jsonPath().getString("status"), "RETURNED");
        Assert.assertNotNull(returnRes.jsonPath().getString("returnDate"), "returnDate must be populated on return");
        Assert.assertEquals(returnRes.jsonPath().getFloat("fineAmount"), 0.0f, 0.01f, "On-time return must have 0.0 fine");

        // Verify Inventory incremented back from 3 -> 4
        Assert.assertEquals(
                getAvailableCopiesByBookId(bookId),
                4,
                "Inventory availableCopies did not restore after return!"
        );
    }


    // ==================== 5. Core Business Rules Tests ====================

    @Test(priority = 3, groups = "regression")
    public void testMaxThreeActiveBorrowsLimitPerMember() {
        String userToken = registerFreshClientToken();

        int book1 = createBookWithInventory(3)[0];
        int book2 = createBookWithInventory(3)[0];
        int book3 = createBookWithInventory(3)[0];
        int book4 = createBookWithInventory(3)[0];

        // Borrow #1 -> 201
        Response res1 = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", book1))
                .post(BORROWS_URL);
        Assert.assertEquals(res1.statusCode(), 201);
        int borrow1Id = res1.jsonPath().getInt("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrow1Id), userToken});

        // Borrow #2 -> 201
        Response res2 = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", book2))
                .post(BORROWS_URL);
        Assert.assertEquals(res2.statusCode(), 201);
        activeBorrowsToCleanup.add(new String[]{String.valueOf(res2.jsonPath().getInt("id")), userToken});

        // Borrow #3 -> 201
        Response res3 = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", book3))
                .post(BORROWS_URL);
        Assert.assertEquals(res3.statusCode(), 201);
        activeBorrowsToCleanup.add(new String[]{String.valueOf(res3.jsonPath().getInt("id")), userToken});

        // Borrow #4 while 3 are ACTIVE -> Must be rejected with 400
        Response res4Blocked = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", book4))
                .post(BORROWS_URL);
        Assert.assertEquals(
                res4Blocked.statusCode(),
                400,
                "BUSINESS RULE BUG: Member exceeded 3 active borrows limit! Response: " + res4Blocked.asString()
        );

        // Return Borrow #1 -> Active borrows drop to 2
        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrow1Id)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(200);

        // Now Borrow #4 must succeed -> 201
        Response res4Allowed = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", book4))
                .post(BORROWS_URL);
        Assert.assertEquals(
                res4Allowed.statusCode(),
                201,
                "Member with 2 active borrows should be allowed to borrow 3rd book: " + res4Allowed.asString()
        );
        activeBorrowsToCleanup.add(new String[]{String.valueOf(res4Allowed.jsonPath().getInt("id")), userToken});
    }

    @Test(priority = 4, groups = "regression")
    public void testCannotBorrowSameBookTwiceSimultaneously() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(5)[0];

        // 1. First borrow -> 201
        Response firstBorrow = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);
        Assert.assertEquals(firstBorrow.statusCode(), 201);
        int borrowId = firstBorrow.jsonPath().getInt("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        // 2. Second borrow of the EXACT SAME book while still ACTIVE -> 400
        Response duplicateBorrow = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);
        Assert.assertEquals(
                duplicateBorrow.statusCode(),
                400,
                "BUSINESS RULE BUG: User borrowed the same book twice at once! Response: " + duplicateBorrow.asString()
        );
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 4, "Stock should only be decremented once");

        // 3. Return the book -> 200
        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(200);

        // 4. Borrowing the same book again after returning must succeed -> 201
        Response reborrowRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);
        Assert.assertEquals(reborrowRes.statusCode(), 201);
        activeBorrowsToCleanup.add(new String[]{String.valueOf(reborrowRes.jsonPath().getInt("id")), userToken});
    }

    @Test(priority = 5, groups = "regression")
    public void testOutOfStockAndMissingInventoryRejected() {
        String userToken = registerFreshClientToken();

        // 1. Book with 0 available copies -> 400
        int zeroStockBookId = createBookWithInventory(0)[0];
        Response zeroRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", zeroStockBookId))
                .post(BORROWS_URL);
        Assert.assertEquals(
                zeroRes.statusCode(),
                400,
                "Expected 400 when borrowing book with 0 copies, got: " + zeroRes.asString()
        );
        Assert.assertEquals(getAvailableCopiesByBookId(zeroStockBookId), 0);

        // 2. Book with NO inventory record at all -> 404 (or 400)
        int noInventoryBookId = createTempBook();
        Response noInvRes = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", noInventoryBookId))
                .post(BORROWS_URL);
        Assert.assertTrue(
                noInvRes.statusCode() == 404 || noInvRes.statusCode() == 400,
                "Expected 404/400 when book has no inventory record, got: " + noInvRes.statusCode()
        );
    }


    // ==================== 6. Bug-Hunting & Concurrency Traps ====================

    @Test(priority = 6, groups = "regression")
    public void testDoubleReturnRejectedAndDoesNotInflateInventory() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(2)[0];

        int borrowId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        // Stock is now 1
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 1);

        // 1. First return -> 200 (Stock restored to 2)
        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(200);
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 2);

        // 2. Second return of the ALREADY RETURNED borrow -> Must be 400 and stock must stay 2 (NOT 3!)
        Response secondReturn = given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL);

        Assert.assertNotEquals(secondReturn.statusCode(), 500, "Server crashed (500) on double return!");
        Assert.assertEquals(
                secondReturn.statusCode(),
                400,
                "BUG: Double return did not return 400! Response: " + secondReturn.asString()
        );
        Assert.assertEquals(
                getAvailableCopiesByBookId(bookId),
                2,
                "CRITICAL INFINITE STOCK BUG: Stock inflated from 2 to " + getAvailableCopiesByBookId(bookId)
        );
    }

    @Test(priority = 7, groups = {"regression", "security-audit"})
    public void testReturnWhenInventoryRecordWasDeletedDoesNotCrashWith500() {
        String userToken = registerFreshClientToken();
        int[] bookAndInv = createBookWithInventory(3);
        int bookId = bookAndInv[0];
        int invId = bookAndInv[1];

        // 1. Borrow the book -> 201
        int borrowId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        // 2. Admin deletes the inventory record while borrow is ACTIVE
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", invId)
                .delete(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(204);
        createdInventoryIds.remove(Integer.valueOf(invId));

        // 3. Client attempts to return the book -> Must not throw unhandled 500 NullPointerException
        Response returnRes = given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL);

        Assert.assertNotEquals(
                returnRes.statusCode(),
                500,
                "BUG (500 NullPointerException): Returning a book whose inventory record was deleted crashed the server! "
                        + returnRes.asString()
        );
    }

    @Test(priority = 8, groups = "regression")
    public void testDeleteBookBlockedWhileActiveBorrowExists() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(2)[0];

        int borrowId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        // Admin attempts to delete book with active borrow -> Must be rejected (409 or 400), never 500
        Response deleteBookRes = BookEndPointes.deleteBook(bookId, adminToken);
        Assert.assertNotEquals(deleteBookRes.statusCode(), 500, "Server crashed (500) deleting book with active borrow!");
        Assert.assertTrue(
                deleteBookRes.statusCode() == 409 || deleteBookRes.statusCode() == 400,
                "Expected 409/400 when deleting book with active borrow, got: " + deleteBookRes.statusCode()
        );
    }

    @Test(priority = 9, groups = {"regression", "security-audit"})
    public void testConcurrentBorrowsEnforceThreeBookLimitPerUser() throws Exception {
        String userToken = registerFreshClientToken();
        int threads = 5;
        int[] bookIds = new int[threads];
        for (int i = 0; i < threads; i++) {
            bookIds[i] = createBookWithInventory(2)[0];
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            final int targetBookId = bookIds[i];
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", targetBookId))
                        .post(BORROWS_URL);
            }));
        }

        latch.countDown();

        int successCount = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) under concurrent borrows!");
            if (res.statusCode() == 201) {
                successCount++;
                activeBorrowsToCleanup.add(new String[]{String.valueOf(res.jsonPath().getInt("id")), userToken});
            }
        }
        executor.shutdown();

        Assert.assertTrue(
                successCount <= 3,
                "CRITICAL CONCURRENCY BUG: User bypassed the 3-book borrow limit under parallel requests! Active borrows created: "
                        + successCount
        );
    }

    @Test(priority = 10, groups = {"regression", "security-audit"})
    public void testConcurrentBorrowsOnLastSingleCopy() throws Exception {
        int bookId = createBookWithInventory(1)[0];
        int threads = 4;

        String[] userTokens = new String[threads];
        for (int i = 0; i < threads; i++) {
            userTokens[i] = registerFreshClientToken();
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            final String token = userTokens[i];
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + token)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bookId))
                        .post(BORROWS_URL);
            }));
        }

        latch.countDown();

        int successCount = 0;
        for (int i = 0; i < threads; i++) {
            Response res = futures.get(i).get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) on concurrent last-copy borrow!");
            if (res.statusCode() == 201) {
                successCount++;
                activeBorrowsToCleanup.add(new String[]{String.valueOf(res.jsonPath().getInt("id")), userTokens[i]});
            }
        }
        executor.shutdown();

        int remaining = getAvailableCopiesByBookId(bookId);
        Assert.assertTrue(remaining >= 0, "CRITICAL RACE CONDITION: Inventory stock went negative (" + remaining + ")!");
        Assert.assertEquals(successCount, 1, "Expected only 1 user to borrow the last available copy!");
        Assert.assertEquals(remaining, 0);
    }


    // ==================== 7. Data-Driven Valid & Invalid Tests ====================

    @Test(
            priority = 11,
            dataProvider = "createValidBorrowData",
            groups = "regression"
    )
    public void testDataDrivenCreateValidBorrows(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int initialStock = testData.get("initialStock").asInt();
        int expectedStatus = testData.get("expectedStatus").asInt();

        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(initialStock)[0];

        Response res = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed valid test case [" + testCase + "] -> Response: " + res.asString()
        );

        if (res.statusCode() == 201) {
            int borrowId = res.jsonPath().getInt("id");
            activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});
            Assert.assertEquals(getAvailableCopiesByBookId(bookId), initialStock - 1);
        }
    }

    @Test(
            priority = 12,
            dataProvider = "createInvalidBorrowData",
            groups = "regression"
    )
    public void testDataDrivenCreateInvalidBorrows(JsonNode testData) {
        String testCase = testData.get("testCase").asText();
        int expectedStatus = testData.get("expectedStatus").asInt();
        Map payload = mapper.convertValue(testData.get("payload"), Map.class);

        Response res = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(BORROWS_URL);

        Assert.assertNotEquals(
                res.statusCode(),
                500,
                "Server crashed (500) on invalid test case: " + testCase
        );

        Assert.assertEquals(
                res.statusCode(),
                expectedStatus,
                "Failed invalid test case [" + testCase + "] -> Response: " + res.asString()
        );
    }


    // ==================== 8. Security & OWASP Audit Tests ====================

    @Test(priority = 13, groups = {"security", "regression"})
    public void testBolaIdorOwnershipEnforcementOnViewAndReturn() {
        String ownerToken = registerFreshClientToken();
        String otherClientToken = registerFreshClientToken();
        int bookId = createBookWithInventory(3)[0];

        int borrowId = given()
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), ownerToken});

        // 1. Another Client CANNOT view owner's borrow record -> 403
        given()
                .header("Authorization", "Bearer " + otherClientToken)
                .pathParam("id", borrowId)
                .when()
                .get(BORROW_BY_ID_URL)
                .then()
                .statusCode(403);

        // 2. Another Client CANNOT return owner's borrow record -> 403
        given()
                .header("Authorization", "Bearer " + otherClientToken)
                .pathParam("id", borrowId)
                .when()
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(403);

        // 3. Admin CAN view owner's borrow record -> 200
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", borrowId)
                .when()
                .get(BORROW_BY_ID_URL)
                .then()
                .statusCode(200)
                .body("id", equalTo(borrowId));

        // 4. Admin CANNOT return a client's book ("Only the borrower can return it") -> 403
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", borrowId)
                .when()
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(403);
    }

    @Test(priority = 14, groups = {"security", "regression"})
    public void testMassAssignmentIgnoredOnCreateBorrow() {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(3)[0];

        Map maliciousPayload = new HashMap();
        maliciousPayload.put("id", 999999);
        maliciousPayload.put("bookId", bookId);
        maliciousPayload.put("status", "RETURNED");
        maliciousPayload.put("fineAmount", -500.0);
        maliciousPayload.put("borrowDate", "2020-01-01");
        maliciousPayload.put("dueDate", "2099-12-31");
        maliciousPayload.put("returnDate", "2026-01-01");

        Response res = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(maliciousPayload)
                .post(BORROWS_URL);

        Assert.assertEquals(res.statusCode(), 201, "Borrow failed: " + res.asString());

        int actualId = res.jsonPath().getInt("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(actualId), userToken});

        Assert.assertNotEquals(actualId, 999999, "MASS ASSIGNMENT BUG: Client supplied borrow ID accepted!");
        Assert.assertEquals(res.jsonPath().getString("status"), "ACTIVE", "MASS ASSIGNMENT BUG: Spoofed status accepted!");
        Assert.assertEquals(res.jsonPath().getFloat("fineAmount"), 0.0f, 0.01f, "MASS ASSIGNMENT BUG: Spoofed fineAmount accepted!");
        Assert.assertNull(res.jsonPath().get("returnDate"), "MASS ASSIGNMENT BUG: Spoofed returnDate accepted!");

        LocalDate actualBorrowDate = LocalDate.parse(res.jsonPath().getString("borrowDate"));
        Assert.assertEquals(
                res.jsonPath().getString("dueDate"),
                actualBorrowDate.plusDays(14).toString(),
                "MASS ASSIGNMENT BUG: Client bypassed 14-day dueDate rule!"
        );
    }

    @Test(priority = 15, groups = {"security", "regression"})
    public void testTamperedAndUnsignedJwtRejectedOnBorrows() {
        int bookId = createBookWithInventory(2)[0];
        Map payload = Map.of("bookId", bookId);

        // 1. Tampered JWT Signature
        String tamperedToken = clientToken.substring(0, clientToken.length() - 4) + "ZZZZ";
        int tamperedStatus = given()
                .header("Authorization", "Bearer " + tamperedToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(BORROWS_URL)
                .statusCode();

        Assert.assertNotEquals(tamperedStatus, 500, "Server crashed (500) on tampered JWT!");
        Assert.assertTrue(tamperedStatus == 401 || tamperedStatus == 403);

        // 2. Unsigned (alg:none) JWT
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String spoofPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin@library.com\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        String unsignedJwt = noneHeader + "." + spoofPayload + ".";

        int unsignedStatus = given()
                .header("Authorization", "Bearer " + unsignedJwt)
                .contentType(ContentType.JSON)
                .body(payload)
                .post(BORROWS_URL)
                .statusCode();

        Assert.assertNotEquals(unsignedStatus, 500, "Server crashed (500) on unsigned JWT!");
        Assert.assertTrue(unsignedStatus == 401 || unsignedStatus == 403);
    }

    @Test(priority = 16, groups = {"security", "security-audit"})
    public void testNonExistentIdsOverflowAndMalformedJsonNo500Leak() {
        int nonExistentId = 9999999;

        // 1. Valid non-existent borrow ID -> 404
        given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", nonExistentId)
                .get(BORROW_BY_ID_URL)
                .then()
                .statusCode(404);

        given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", nonExistentId)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(404);

        // 2. Malformed JSON -> 400
        // 2. Malformed JSON -> 400
        String brokenJson = "{\"bookId\": ";
        given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(brokenJson)
                .post(BORROWS_URL)
                .then()
                .statusCode(400);

        // 3. Path Injection & Long Overflow -> Must not return 500 or stack trace
        String[] maliciousIds = {"99999999999999999999", "-1", "abc", "1'OR'1'='1"};
        for (String badId : maliciousIds) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .get(BORROWS_URL + "/" + badId);

            String body = res.asString();
            Assert.assertNotEquals(
                    res.statusCode(),
                    500,
                    "BUG (500) on GET /api/borrows/" + badId + " -> " + body
            );
            Assert.assertFalse(
                    body.contains("java.lang.") || body.contains("org.springframework."),
                    "SECURITY FINDING: Stack trace leaked for borrow ID [" + badId + "]: " + body
            );
        }
    }

    @Test(priority = 17, groups = "security-audit")
    public void testMyBorrowsRateLimitingAudit() {
        int maxRequests = 50;
        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 1; i <= maxRequests; i++) {
            Response res = given()
                    .header("Authorization", "Bearer " + clientToken)
                    .get(MY_BORROWS_URL);

            int status = res.statusCode();
            Assert.assertNotEquals(status, 500, "Server crashed (500) on GET /api/borrows/my at request #" + i);

            if (status == 429) {
                rateLimitedCount++;
                break;
            } else if (status == 200) {
                successCount++;
            }
        }

        Assert.assertTrue(
                rateLimitedCount > 0,
                "SECURITY FINDING (OWASP API4): No Rate Limiting (429) enforced on GET /api/borrows/my after "
                        + successCount + " rapid requests!"
        );
    }
    // ==================== 9. Additional Concurrency, Security & Edge Cases ====================

    @Test(priority = 18, groups = {"regression", "security-audit"})
    public void testConcurrentParallelReturnDoesNotInflateInventory() throws Exception {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(2)[0];

        int borrowId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userToken});

        // Stock is now 1
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 1);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + userToken)
                        .pathParam("id", borrowId)
                        .put(RETURN_BORROW_URL);
            }));
        }

        latch.countDown();

        int successReturns = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) on concurrent return!");
            if (res.statusCode() == 200) {
                successReturns++;
            }
        }
        executor.shutdown();

        int finalStock = getAvailableCopiesByBookId(bookId);
        Assert.assertEquals(
                successReturns,
                1,
                "CONCURRENCY BUG: Multiple parallel return requests succeeded (" + successReturns + ")!"
        );
        Assert.assertEquals(
                finalStock,
                2,
                "CRITICAL INFINITE STOCK BUG: Parallel returns inflated stock from 1 to " + finalStock
        );
    }

    @Test(priority = 19, groups = {"regression", "security-audit"})
    public void testConcurrentSameBookBorrowBySameUserBlocked() throws Exception {
        String userToken = registerFreshClientToken();
        int bookId = createBookWithInventory(5)[0];

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return given()
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bookId))
                        .post(BORROWS_URL);
            }));
        }

        latch.countDown();

        int successCount = 0;
        for (Future<Response> f : futures) {
            Response res = f.get();
            Assert.assertNotEquals(res.statusCode(), 500, "Server crashed (500) on parallel duplicate book borrow!");
            if (res.statusCode() == 201) {
                successCount++;
                activeBorrowsToCleanup.add(new String[]{String.valueOf(res.jsonPath().getInt("id")), userToken});
            }
        }
        executor.shutdown();

        Assert.assertEquals(
                successCount,
                1,
                "CONCURRENCY BUG: Same user borrowed the exact same book " + successCount + " times simultaneously!"
        );
        Assert.assertEquals(getAvailableCopiesByBookId(bookId), 4);
    }

    @Test(priority = 20, groups = {"security", "regression"})
    public void testMyBorrowsIsolationAndAdminVisibilityAndNoPasswordLeak() {
        String userAToken = registerFreshClientToken();
        String userBToken = registerFreshClientToken();
        int bookId = createBookWithInventory(3)[0];

        Response createRes = given()
                .header("Authorization", "Bearer " + userAToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL);
        Assert.assertEquals(createRes.statusCode(), 201);

        String createBody = createRes.asString();
        Assert.assertFalse(
                createBody.contains("\"password\"") || createBody.contains("passwordHash"),
                "SECURITY FINDING: Sensitive password field leaked in POST /api/borrows response!"
        );

        int borrowId = createRes.jsonPath().getInt("id");
        activeBorrowsToCleanup.add(new String[]{String.valueOf(borrowId), userAToken});

        // 1. User B's /api/borrows/my must NOT contain User A's borrow
        List<Integer> userBBorrows = given()
                .header("Authorization", "Bearer " + userBToken)
                .get(MY_BORROWS_URL)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Assert.assertFalse(
                userBBorrows.contains(borrowId),
                "CRITICAL BOLA BUG: User A's borrow record appeared in User B's /api/borrows/my!"
        );

        // 2. Admin's GET /api/borrows MUST contain User A's borrow and never leak passwords
        Response adminAllRes = given()
                .header("Authorization", "Bearer " + adminToken)
                .get(BORROWS_URL);
        Assert.assertEquals(adminAllRes.statusCode(), 200);
        Assert.assertFalse(
                adminAllRes.asString().contains("\"password\""),
                "SECURITY FINDING: Password leaked in GET /api/borrows!"
        );
        List<Integer> allBorrowIds = adminAllRes.jsonPath().getList("id");
        Assert.assertTrue(allBorrowIds.contains(borrowId), "Borrow record missing from Admin GET /api/borrows");
    }

    @Test(priority = 21, groups = {"security", "regression"})
    public void testUnauthenticatedAccessBlockedOnAllBorrowEndpoints() {
        int bookId = createBookWithInventory(2)[0];

        // 1. Unauthenticated POST /api/borrows
        int postStatus = given()
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .statusCode();
        Assert.assertTrue(postStatus == 401 || postStatus == 403, "Expected 401/403 on unauth POST /borrows, got: " + postStatus);

        // 2. Unauthenticated GET /api/borrows/{id}
        int getByIdStatus = given()
                .pathParam("id", 1)
                .get(BORROW_BY_ID_URL)
                .statusCode();
        Assert.assertTrue(getByIdStatus == 401 || getByIdStatus == 403, "Expected 401/403 on unauth GET /borrows/{id}, got: " + getByIdStatus);

        // 3. Unauthenticated PUT /api/borrows/{id}/return
        int returnStatus = given()
                .pathParam("id", 1)
                .put(RETURN_BORROW_URL)
                .statusCode();
        Assert.assertTrue(returnStatus == 401 || returnStatus == 403, "Expected 401/403 on unauth PUT /borrows/{id}/return, got: " + returnStatus);
    }

    @Test(priority = 22, groups = {"security", "security-audit"})
    public void testUnsupportedHttpMethodsOnBorrows() {

        Response wrongReturnMethod = given()
                .header("Authorization", "Bearer " + clientToken)
                .pathParam("id", 1)
                .post(RETURN_BORROW_URL);
        Assert.assertNotEquals(
                wrongReturnMethod.statusCode(),
                500,
                "BUG (500 on POST /borrows/{id}/return): " + wrongReturnMethod.asString()
        );

        // 2. DELETE on /api/borrows/1
        Response deleteBorrowMethod = given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", 1)
                .delete(BORROW_BY_ID_URL);
        Assert.assertNotEquals(
                deleteBorrowMethod.statusCode(),
                500,
                "BUG (500 on DELETE /borrows/{id}): " + deleteBorrowMethod.asString()
        );
    }

    @Test(priority = 23, groups = "regression")
    public void testReturnedBorrowSurvivesBookAndInventoryDeletion() {
        String userToken = registerFreshClientToken();
        int[] bookAndInv = createBookWithInventory(2);
        int bookId = bookAndInv[0];
        int invId = bookAndInv[1];

        // 1. Borrow and return the book
        int borrowId = given()
                .header("Authorization", "Bearer " + userToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", bookId))
                .post(BORROWS_URL)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .header("Authorization", "Bearer " + userToken)
                .pathParam("id", borrowId)
                .put(RETURN_BORROW_URL)
                .then()
                .statusCode(200);

        // 2. Delete inventory and book after return
        given()
                .header("Authorization", "Bearer " + adminToken)
                .pathParam("id", invId)
                .delete(INVENTORY_BY_ID_URL)
                .then()
                .statusCode(204);
        createdInventoryIds.remove(Integer.valueOf(invId));

        Response deleteBookRes = BookEndPointes.deleteBook(bookId, adminToken);
        if (deleteBookRes.statusCode() == 204) {
            createdBookIds.remove(Integer.valueOf(bookId));
        }

        // 3. User's borrow history must still load cleanly without 500
        Response historyRes = given()
                .header("Authorization", "Bearer " + userToken)
                .get(MY_BORROWS_URL);
        Assert.assertEquals(
                historyRes.statusCode(),
                200,
                "Historical borrow endpoint broke after book deletion: " + historyRes.asString()
        );
    }


    // ==================== 9. Cleanup ====================

    @AfterClass(alwaysRun = true)
    public void cleanupBorrowsInventoryAndBooks() {
        // 1. Return any active borrows first
        for (String[] entry : new ArrayList<String[]>(activeBorrowsToCleanup)) {
            try {
                given()
                        .header("Authorization", "Bearer " + entry[1])
                        .pathParam("id", Integer.parseInt(entry[0]))
                        .put(RETURN_BORROW_URL);
            } catch (Exception ignored) {
            }
        }
        activeBorrowsToCleanup.clear();

        // 2. Delete inventory records
        for (Integer invId : new ArrayList<Integer>(createdInventoryIds)) {
            if (invId != null && invId > 0) {
                try {
                    given()
                            .header("Authorization", "Bearer " + adminToken)
                            .pathParam("id", invId)
                            .delete(INVENTORY_BY_ID_URL);
                } catch (Exception ignored) {
                }
            }
        }
        createdInventoryIds.clear();

        // 3. Delete temporary books
        for (Integer bookId : new ArrayList<Integer>(createdBookIds)) {
            if (bookId != null && bookId > 0) {
                try {
                    BookEndPointes.deleteBook(bookId, adminToken);
                } catch (Exception ignored) {
                }
            }
        }
        createdBookIds.clear();
    }
}