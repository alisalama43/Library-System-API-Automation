package api.test.Auth;

import api.endpoints.*;
import api.payloads.*;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.restassured.RestAssured.given;

public class SecurityTests extends BaseTest {

    private String client2Token;
    private int testBookId;
    private int testInventoryId;

    private int client1ReviewId;
    private int client1ReservationId;
    private int client1BorrowId;
    private int client1OrderId;
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }
    @BeforeClass
    public void setupSecurityData() {
        // 1. تسجيل مستخدم ثانٍ (Client B) لاختبارات BOLA
        String email2 = "client_b_" + System.currentTimeMillis() + "@test.com";
        RegisterRequest regPayload = new RegisterRequest("Client B User", email2, "Secret@123");
        Response regRes = AuthEndpoints.register(regPayload);
        Assert.assertTrue(regRes.statusCode() == 200 || regRes.statusCode() == 201, "Failed to register Client B");
        client2Token = regRes.jsonPath().getString("token");

        // 2. إنشاء كتاب ومخزون كـ Admin لتنفيذ العمليات عليهما
        BookRequest bookPayload = new BookRequest(
                "Security Audit Book",
                "Security Author",
                2024,
                "Tech Press",
                "Security",
                null,
                100.0,
                "Book used for BOLA and security verification"
        );
        Response bookRes = BookEndPointes.createBook(bookPayload, adminToken);
        testBookId = bookRes.jsonPath().getInt("id");

        Response invRes = InventoryEndpoints.createInventory(new InventoryRequest(testBookId, 10), adminToken);
        testInventoryId = invRes.jsonPath().getInt("id");

        // 3. إنشاء موارد باسم المستخدم الأول (Client A)
        Response revRes = ReviewsEndpoints.createReview(new ReviewRequest(5, "Great security book"), clientToken);
        if (revRes.statusCode() == 200 || revRes.statusCode() == 201) {
            client1ReviewId = revRes.jsonPath().getInt("id");
        }

        Response resvRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", testBookId))
                .post(Routes.CREATE_RESERVATION);
        if (resvRes.statusCode() == 200 || resvRes.statusCode() == 201) {
            client1ReservationId = resvRes.jsonPath().getInt("id");
        }

        Response borrowRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(Map.of("bookId", testBookId))
                .post(Routes.CREATE_BORROW);
        if (borrowRes.statusCode() == 200 || borrowRes.statusCode() == 201) {
            client1BorrowId = borrowRes.jsonPath().getInt("id");
        }

        Map<String, Object> orderBody = Map.of(
                "items", List.of(Map.of("bookId", testBookId, "quantity", 1))
        );
        Response orderRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(orderBody)
                .post(Routes.CREATE_ORDER);
        if (orderRes.statusCode() == 200 || orderRes.statusCode() == 201) {
            client1OrderId = orderRes.jsonPath().getInt("id");
        }
    }

    // ==================== 1. Authentication & JWT Security ====================

    @Test(priority = 1)
    public void userEnumerationTest() {
        Response existingUser = AuthEndpoints.login(
                new LoginRequest("admin@library.com", "WrongPassword123")
        );

        Response unknownUser = AuthEndpoints.login(
                new LoginRequest("unknown_" + System.currentTimeMillis() + "@test.com", "WrongPassword123")
        );

        Assert.assertEquals(
                existingUser.statusCode(),
                unknownUser.statusCode(),
                "Different status codes may reveal account existence."
        );
    }

    @Test(priority = 2)
    public void sensitiveInformationExposureOnLoginTest() {
        Response response = AuthEndpoints.login(
                new LoginRequest("admin@library.com", "Admin@12345")
        );

        String responseBody = response.asString();
        Assert.assertFalse(responseBody.contains("Admin@12345"), "Password must not appear in response.");
        Assert.assertFalse(responseBody.toLowerCase().contains("password"), "Response may expose sensitive password info.");
        Assert.assertFalse(responseBody.toLowerCase().contains("secretkey"), "Secret key must not be exposed.");
    }

    @Test(priority = 3)
    public void jwtTokenTamperingAndNoneAlgorithmTest() {
        String[] parts = clientToken.split("\\.");
        Assert.assertEquals(parts.length, 3);

        // 1. تعديل حرف في الـ Payload مع الإبقاء على التوقيع الأصلي
        byte[] decodedPayload = Base64.getUrlDecoder().decode(parts[1]);
        String payload = new String(decodedPayload, StandardCharsets.UTF_8);
        String modifiedPayload = payload.replaceFirst("\"", "\"x");
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(modifiedPayload.getBytes(StandardCharsets.UTF_8));

        String tamperedToken = parts[0] + "." + encodedPayload + "." + parts[2];

        Response tamperedRes = AuthEndpoints.adminRegister(
                new RegisterRequest("Tampered User", "tampered_" + System.currentTimeMillis() + "@test.com", "StrongPassword123"),
                tamperedToken
        );
        Assert.assertTrue(tamperedRes.statusCode() == 401 || tamperedRes.statusCode() == 403, "Tampered JWT must not be accepted.");

        // 2. هجوم alg: none بدون توقيع رقمي
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String adminPayload = payload.replace("CLIENT", "ADMIN");
        String encodedAdminPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(adminPayload.getBytes(StandardCharsets.UTF_8));

        String unsignedToken = noneHeader + "." + encodedAdminPayload + ".";

        Response noneAlgRes = given()
                .header("Authorization", "Bearer " + unsignedToken)
                .get(Routes.GET_ALL_ORDERS);
        Assert.assertTrue(noneAlgRes.statusCode() == 401 || noneAlgRes.statusCode() == 403, "Server accepted unsigned JWT (alg:none)!");
    }

    @Test(priority = 4)
    public void invalidSignatureTest() {
        String[] parts = clientToken.split("\\.");
        String invalidSignature = parts[2] + "invalid";
        String invalidToken = parts[0] + "." + parts[1] + "." + invalidSignature;

        Response response = AuthEndpoints.adminRegister(
                new RegisterRequest("Invalid Signature User", "signature_" + System.currentTimeMillis() + "@test.com", "StrongPassword123"),
                invalidToken
        );

        Assert.assertTrue(response.statusCode() == 401 || response.statusCode() == 403, "Invalid JWT signature must be rejected.");
    }

    @DataProvider(name = "sqlInjectionPayloads")
    public Object[][] sqlInjectionPayloads() {
        return new Object[][]{
                {"' OR '1'='1", "anything"},
                {"admin@library.com' --", "anything"},
                {"' OR 1=1 --", "anything"},
                {"admin' OR '1'='1' --", "wrongPassword"},
                {"' UNION SELECT NULL --", "anything"},
                {"<script>alert('XSS')</script>", "anything"}
        };
    }

    @Test(priority = 5, dataProvider = "sqlInjectionPayloads")
    public void sqlInjectionAndXssLoginTest(String email, String password) {
        Response response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .when()
                .post(Routes.LOGIN);

        Assert.assertNotEquals(response.statusCode(), 500, "Possible server-side SQL/input handling issue");
        Assert.assertTrue(response.statusCode() == 400 || response.statusCode() == 401, "Unexpected authentication response");
    }

    @Test(priority = 6)
    public void testBolaClientBCannotUpdateOrDeleteClientAReview() {
        if (client1ReviewId == 0) return;

        ReviewRequest maliciousUpdate = new ReviewRequest(1, "Hacked review comment");
        Assert.assertEquals(ReviewsEndpoints.updateReview(client1ReviewId, maliciousUpdate, client2Token).statusCode(), 403,
                "BOLA: Client B updated Client A's review!");
        Assert.assertEquals(ReviewsEndpoints.deleteReview(client1ReviewId, client2Token).statusCode(), 403,
                "BOLA: Client B deleted Client A's review!");
    }

    @Test(priority = 7)
    public void testBolaClientBCannotCancelClientAReservation() {
        if (client1ReservationId == 0) return;

        Assert.assertEquals(ReservationsEndpoints.cancelReservation(client1ReservationId, client2Token).statusCode(), 403,
                "BOLA: Client B cancelled Client A's reservation!");
    }

    @Test(priority = 8)
    public void testBolaClientBCannotViewOrReturnClientABorrow() {
        if (client1BorrowId == 0) return;

        int viewStatus = BorrowsEndpoints.getBorrowById(client1BorrowId, client2Token).statusCode();
        Assert.assertTrue(viewStatus == 403 || viewStatus == 404, "BOLA: Client B viewed Client A's borrow!");

        Assert.assertEquals(BorrowsEndpoints.returnBorrow(client1BorrowId, client2Token).statusCode(), 403,
                "BOLA: Client B returned Client A's book!");
    }

    @Test(priority = 9)
    public void testBolaClientBCannotViewClientAOrder() {
        if (client1OrderId == 0) return;

        int orderStatus = OrdersEndpoints.getOrderById(client1OrderId, client2Token).statusCode();
        Assert.assertTrue(orderStatus == 403 || orderStatus == 404, "BOLA: Client B accessed Client A's order!");
    }

    // ==================== 3. BFLA Tests (Vertical Privilege Escalation) ====================

    @Test(priority = 10)
    public void testBflaClientCannotAccessAdminEndpoints() {
        Response adminRegAttempt = AuthEndpoints.adminRegister(
                new RegisterRequest("Escalated Admin", "esc_" + System.currentTimeMillis() + "@test.com", "Pass@123"),
                clientToken
        );
        Assert.assertEquals(adminRegAttempt.statusCode(), 403, "BFLA: Client accessed adminRegister!");
        Assert.assertEquals(OrdersEndpoints.getAllOrders(clientToken).statusCode(), 403, "BFLA: Client accessed getAllOrders!");
        Assert.assertEquals(ReservationsEndpoints.getAllReservations(clientToken).statusCode(), 403, "BFLA: Client accessed getAllReservations!");
        Assert.assertEquals(BorrowsEndpoints.getAllBorrows(clientToken).statusCode(), 403, "BFLA: Client accessed getAllBorrows!");
    }

    // ==================== 4. Mass Assignment & JSON Parameter Pollution ====================

    @Test(priority = 11)
    public void testMassAssignmentRoleEscalationOnPublicRegister() {
        String email = "mass_assign_" + System.currentTimeMillis() + "@test.com";

        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", "Normal User");
        payload.put("email", email);
        payload.put("password", "Password@123");
        payload.put("role", "ADMIN");

        Response response = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .post(Routes.REGISTER);

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            Assert.assertEquals(response.jsonPath().getString("role"), "CLIENT",
                    "CRITICAL: Mass Assignment allowed registering as ADMIN!");
        } else {
            Assert.assertEquals(response.statusCode(), 400);
        }
    }

    @Test(priority = 12)
    public void testJsonParameterPollutionAttacks() {
        // 1. تكرار حقل role في التسجيل: {"role": "CLIENT", "role": "ADMIN"}
        String email = "pollution_" + System.currentTimeMillis() + "@test.com";
        String rawDuplicateRoleJson = String.format(
                "{\"fullName\":\"Pollution User\",\"email\":\"%s\",\"password\":\"Password@123\",\"role\":\"CLIENT\",\"role\":\"ADMIN\"}",
                email
        );

        Response registerRes = given()
                .contentType(ContentType.JSON)
                .body(rawDuplicateRoleJson)
                .post(Routes.REGISTER);

        if (registerRes.statusCode() == 200 || registerRes.statusCode() == 201) {
            Assert.assertEquals(registerRes.jsonPath().getString("role"), "CLIENT",
                    "CRITICAL: Duplicate JSON key pollution granted ADMIN role!");
        } else {
            Assert.assertEquals(registerRes.statusCode(), 400);
        }

        // 2. تكرار حقل quantity بقيمة موجبة ثم سالبة
        String rawDuplicateQtyJson = String.format(
                "{\"items\":[{\"bookId\":%d,\"quantity\":1,\"quantity\":-10}]}",
                testBookId
        );

        Response orderRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(rawDuplicateQtyJson)
                .post(Routes.CREATE_ORDER);

        Assert.assertEquals(orderRes.statusCode(), 400,
                "JSON Parameter Pollution allowed negative quantity!");
    }

    // ==================== 5. Business Logic Security Flaws ====================

    @Test(priority = 13)
    public void testNegativeQuantityAndPriceManipulationOnOrders() {
        Response initialInv = InventoryEndpoints.getInventoryByBookId(testBookId);
        int initialCopies = initialInv.statusCode() == 200 ? initialInv.jsonPath().getInt("availableCopies") : -1;

        // 1. كمية سالبة (-5)
        Map<String, Object> negativeOrder = Map.of(
                "items", List.of(Map.of("bookId", testBookId, "quantity", -5))
        );

        Response negRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(negativeOrder)
                .post(Routes.CREATE_ORDER);

        Assert.assertEquals(negRes.statusCode(), 400, "Server must reject negative order quantity!");

        if (initialCopies >= 0) {
            int afterNegCopies = InventoryEndpoints.getInventoryByBookId(testBookId).jsonPath().getInt("availableCopies");
            Assert.assertEquals(afterNegCopies, initialCopies, "CRITICAL: Negative quantity altered inventory stock!");
        }

        // 2. التلاعب بالسعر داخل الطلب
        Map<String, Object> manipulatedPriceOrder = Map.of(
                "items", List.of(Map.of("bookId", testBookId, "quantity", 1, "price", 0.01, "unitPrice", 0.01)),
                "totalPrice", 0.01
        );

        Response priceRes = given()
                .header("Authorization", "Bearer " + clientToken)
                .contentType(ContentType.JSON)
                .body(manipulatedPriceOrder)
                .post(Routes.CREATE_ORDER);

        if (priceRes.statusCode() == 200 || priceRes.statusCode() == 201) {
            Assert.assertFalse(priceRes.asString().contains("0.01"), "CRITICAL: Server accepted client-manipulated price!");
        }
    }

    @Test(priority = 14)
    public void testResourceExhaustionQuotaLimitOnBorrowsAndReservations() {
        String quotaEmail = "quota_user_" + System.currentTimeMillis() + "@test.com";
        String quotaUserToken = AuthEndpoints.register(new RegisterRequest("Quota Tester", quotaEmail, "Secret@123"))
                .jsonPath().getString("token");

        BookRequest bulkBook = new BookRequest(
                "Quota Limit Book", "Quota Author", 2024, null, null, null, 50.0, null
        );
        int bulkBookId = BookEndPointes.createBook(bulkBook, adminToken).jsonPath().getInt("id");
        int bulkInvId = InventoryEndpoints.createInventory(new InventoryRequest(bulkBookId, 50), adminToken)
                .jsonPath().getInt("id");

        List<Integer> createdBorrowIds = new ArrayList<>();
        List<Integer> createdReservationIds = new ArrayList<>();
        boolean quotaLimitEnforced = false;

        try {
            for (int i = 1; i <= 50; i++) {
                Response borrowRes = given()
                        .header("Authorization", "Bearer " + quotaUserToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bulkBookId))
                        .post(Routes.CREATE_BORROW);

                if (borrowRes.statusCode() == 200 || borrowRes.statusCode() == 201) {
                    createdBorrowIds.add(borrowRes.jsonPath().getInt("id"));
                } else if (borrowRes.statusCode() == 400 || borrowRes.statusCode() == 403
                        || borrowRes.statusCode() == 409 || borrowRes.statusCode() == 429) {
                    quotaLimitEnforced = true;
                    break;
                }

                Response resvRes = given()
                        .header("Authorization", "Bearer " + quotaUserToken)
                        .contentType(ContentType.JSON)
                        .body(Map.of("bookId", bulkBookId))
                        .post(Routes.CREATE_RESERVATION);

                if (resvRes.statusCode() == 200 || resvRes.statusCode() == 201) {
                    createdReservationIds.add(resvRes.jsonPath().getInt("id"));
                } else if (resvRes.statusCode() == 400 || resvRes.statusCode() == 403
                        || resvRes.statusCode() == 409 || resvRes.statusCode() == 429) {
                    quotaLimitEnforced = true;
                    break;
                }
            }

            Assert.assertTrue(
                    quotaLimitEnforced,
                    "SECURITY FINDING (OWASP API4): User created " + createdBorrowIds.size()
                            + " borrows and " + createdReservationIds.size() + " reservations without quota limit!"
            );
        } finally {
            for (int borrowId : createdBorrowIds) {
                BorrowsEndpoints.returnBorrow(borrowId, quotaUserToken);
            }
            for (int resvId : createdReservationIds) {
                ReservationsEndpoints.cancelReservation(resvId, quotaUserToken);
            }
            InventoryEndpoints.deleteInventory(bulkInvId, adminToken);
            BookEndPointes.deleteBook(bulkBookId, adminToken);
        }
    }

    // ==================== 6. Excessive Data Exposure & Rate Limiting ====================

    @Test(priority = 15)
    public void testExcessiveDataExposureAcrossEndpoints() {
        Response reviewsRes = ReviewsEndpoints.getReviewsByBookId(testBookId);
        Response ordersRes = OrdersEndpoints.getMyOrders(clientToken);

        String combined = (reviewsRes.asString() + ordersRes.asString()).toLowerCase();
        Assert.assertFalse(combined.contains("\"password\""), "Password leaked in response!");
        Assert.assertFalse(combined.contains("passwordhash"), "Password hash leaked in response!");
        Assert.assertFalse(combined.contains("\"secret\""), "Secret field leaked in response!");
    }

    @Test(priority = 16, groups = "security-audit")
    public void testLoginBruteForceAndRateLimiting() {
        int blockedRequests = 0;

        for (int i = 1; i <= 25; i++) {
            Response response = AuthEndpoints.login(
                    new LoginRequest("bruteforce_target@library.com", "WrongPassword" + i)
            );

            Assert.assertNotEquals(response.statusCode(), 500, "Server crashed under brute-force attempts!");

            if (response.statusCode() == 429) {
                blockedRequests++;
                System.out.println("Rate limiting triggered at attempt #" + i);
                break;
            }
        }


        Assert.assertTrue(
                blockedRequests > 0,
                "SECURITY FINDING (API4): No Rate Limiting (429 Too Many Requests) detected after 25 failed login attempts!"
        );
    }
    @Test(priority = 17, groups = "security-audit")
    public void testRegisterRateLimitingProtection() {
        int rateLimitedCount = 0;
        int createdAccountsCount = 0;

        for (int i = 1; i <= 25; i++) {
            String uniqueEmail = "ratelimit_reg_" + System.currentTimeMillis() + "_" + i + "@test.com";
            RegisterRequest payload = new RegisterRequest(
                    "Rate Limit Tester",
                    uniqueEmail,
                    "StrongPass@123"
            );

            Response response = AuthEndpoints.register(payload);
            int status = response.statusCode();

            Assert.assertNotEquals(status, 500, "Server crashed (500) during rapid registration attempts!");

            if (status == 429) {
                rateLimitedCount++;
                System.out.println("Register rate limiting triggered at attempt #" + i);
                break;
            } else if (status == 200 || status == 201) {
                createdAccountsCount++;
            }
        }

      Assert.assertTrue(
                rateLimitedCount > 0,
                "SECURITY FINDING (OWASP API4): No Rate Limiting (429 Too Many Requests) on POST /api/auth/register! Created "
                        + createdAccountsCount + " accounts rapidly without throttling."
        );
    }

    // ==================== Cleanup ====================

    @AfterClass
    public void cleanupSecurityData() {
        if (client1ReviewId > 0) ReviewsEndpoints.deleteReview(client1ReviewId, adminToken);
        if (client1ReservationId > 0) ReservationsEndpoints.cancelReservation(client1ReservationId, clientToken);
        if (client1BorrowId > 0) BorrowsEndpoints.returnBorrow(client1BorrowId, clientToken);
        if (testInventoryId > 0) InventoryEndpoints.deleteInventory(testInventoryId, adminToken);
        if (testBookId > 0) BookEndPointes.deleteBook(testBookId, adminToken);
    }
}