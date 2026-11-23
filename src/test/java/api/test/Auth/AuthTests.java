package api.test.Auth;

import api.endpoints.AuthEndpoints;
import api.endpoints.Routes;
import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import api.utilites.JsonDataReader;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;

public class AuthTests extends BaseTest {

    private static final String DATA_PATH = "src/test/resources/testdata/authdata.json";
    private String registeredValidEmail;
    private final String registeredValidPassword = "secret123";

    private final String edgeCaseBody = """
            {
              "fullName": "Edge Case Admin",
              "email": "edge.case@example.com",
              "password": "Admin@12345"
            }
            """;

    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    // ==================== Data Providers ====================

    @DataProvider(name = "registerData")
    public Object[][] getRegisterData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "registerCases");
    }

    @DataProvider(name = "loginData")
    public Object[][] getLoginData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "loginCases");
    }

    @DataProvider(name = "adminRegisterData")
    public Object[][] getAdminRegisterData() throws IOException {
        return JsonDataReader.getJsonData(DATA_PATH, "adminRegisterCases");
    }

    // ==================== Helper Methods ====================

    private Response sendRawAuthRequest(String authorizationHeader) {
        return given()
                .header("Authorization", authorizationHeader)
                .contentType(ContentType.JSON)
                .body(edgeCaseBody)
                .when()
                .post(Routes.ADMIN_REGISTER);
    }

    private RegisterRequest createAdminPayload() {
        String email = "admin_test_" + System.currentTimeMillis() + "@test.com";
        return new RegisterRequest("Test Admin", email, "StrongPassword123");
    }

    // ==================== 1. Data-Driven Core Tests ====================

    @Test(priority = 1, dataProvider = "registerData")
    public void testRegisterDataDriven(Map<String, Object> data) {
        String fullName = (String) data.get("fullName");
        String emailPrefix = (String) data.get("emailPrefix");
        String password = (String) data.get("password");
        int expectedStatus = (int) data.get("expectedStatusCode");
        String expectedError = (String) data.get("expectedError");

        String email = emailPrefix.contains("@") || emailPrefix.equals("invalid-email-format")
                ? emailPrefix
                : emailPrefix + "_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest payload = new RegisterRequest(fullName, email, password);
        Response res = AuthEndpoints.register(payload);

        Assert.assertEquals(res.statusCode(), expectedStatus, "Failed on case: " + data.get("testCaseName"));

        if (expectedStatus == 200 || expectedStatus == 201) {
            Assert.assertNotNull(res.jsonPath().getString("token"));
            Assert.assertEquals(res.jsonPath().getString("role"), "CLIENT");
            registeredValidEmail = email;
        } else {
            Assert.assertEquals(res.jsonPath().getString("error"), expectedError);
        }
    }

    @Test(priority = 2, dependsOnMethods = "testRegisterDataDriven")
    public void testRegisterDuplicateEmailReturns409() {
        RegisterRequest payload = new RegisterRequest("Ann Ali", registeredValidEmail, registeredValidPassword);
        Response res = AuthEndpoints.register(payload);

        Assert.assertEquals(res.statusCode(), 409);
        Assert.assertEquals(res.jsonPath().getString("error"), "Conflict");
    }

    @Test(priority = 3, dataProvider = "loginData", dependsOnMethods = "testRegisterDataDriven")
    public void testLoginDataDriven(Map<String, Object> data) {
        boolean useValidUser = (boolean) data.get("useValidUser");
        String email = useValidUser ? registeredValidEmail : (String) data.get("email");
        String password = (String) data.get("password");
        int expectedStatus = (int) data.get("expectedStatusCode");
        String expectedError = (String) data.get("expectedError");

        LoginRequest payload = new LoginRequest(email, password);
        Response res = AuthEndpoints.login(payload);

        Assert.assertEquals(res.statusCode(), expectedStatus, "Failed on case: " + data.get("testCaseName"));

        if (expectedStatus == 200) {
            Assert.assertNotNull(res.jsonPath().getString("token"));
            Assert.assertEquals(res.jsonPath().getString("email"), registeredValidEmail);
        } else {
            Assert.assertEquals(res.jsonPath().getString("error"), expectedError);
        }
    }

    @Test(priority = 4, dataProvider = "adminRegisterData")
    public void testAdminRegisterDataDriven(Map<String, Object> data) {
        String fullName = (String) data.get("fullName");
        String email = data.get("emailPrefix") + "_" + System.currentTimeMillis() + "@test.com";
        String password = (String) data.get("password");
        String roleToken = (String) data.get("roleToken");
        int expectedStatus = (int) data.get("expectedStatusCode");

        String tokenToUse = roleToken.equals("ADMIN") ? adminToken : clientToken;

        RegisterRequest payload = new RegisterRequest(fullName, email, password);
        Response res = AuthEndpoints.adminRegister(payload, tokenToUse);

        Assert.assertEquals(res.statusCode(), expectedStatus, "Failed on case: " + data.get("testCaseName"));

        if (expectedStatus == 200 || expectedStatus == 201) {
            Assert.assertEquals(res.jsonPath().getString("email"), email);
        }
    }

    // ==================== 2. Header & Edge Case Tests ====================

    @Test(priority = 5)
    public void multipleAuthorizationHeadersTest() {
        Response response = given()
                .header("Authorization", "Bearer invalidToken")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(edgeCaseBody)
                .when()
                .post(Routes.ADMIN_REGISTER);

        Assert.assertTrue(
                response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403,
                "Unexpected response: " + response.statusCode()
        );
    }

    @Test(priority = 6)
    public void duplicateBearerPrefixTest() {
        Response response = sendRawAuthRequest("Bearer Bearer " + adminToken);

        Assert.assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403,
                "Unexpected response: " + response.statusCode()
        );
    }

    @Test(priority = 7)
    public void veryLongTokenTest() {
        String longToken = "A".repeat(10000);
        Response response = sendRawAuthRequest("Bearer " + longToken);

        Assert.assertTrue(
                response.statusCode() == 400 || response.statusCode() == 401 ||
                        response.statusCode() == 403 || response.statusCode() == 431,
                "Unexpected response: " + response.statusCode()
        );
    }

    @Test(priority = 8)
    public void unicodeAuthorizationHeaderTest() {
        try {
            Response response = sendRawAuthRequest("Bearer токен123");
            Assert.assertTrue(
                    response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403,
                    "Unexpected response: " + response.statusCode()
            );
        } catch (IllegalArgumentException exception) {
            System.out.println("Request rejected by HTTP client: " + exception.getMessage());
        }
    }

    // ==================== 3. Authorization & JWT Security Tests ====================

    @Test(priority = 9)
    public void createAdminWithoutToken() {
        Response response = given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(createAdminPayload())
                .when()
                .post(Routes.ADMIN_REGISTER);

        Assert.assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403,
                "Request without token should return 401 or 403. Actual: " + response.statusCode()
        );
    }

    @Test(priority = 10)
    public void createAdminUsingBasicScheme() {
        Response response = given()
                .header("Authorization", "Basic " + adminToken)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(createAdminPayload())
                .when()
                .post(Routes.ADMIN_REGISTER);

        Assert.assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403,
                "Basic authentication should not be accepted. Actual: " + response.statusCode()
        );
    }

    @Test(priority = 11)
    public void clientCannotManipulateRoleToAdmin() {
        String[] parts = clientToken.split("\\.");
        Assert.assertEquals(parts.length, 3, "Invalid JWT format");

        byte[] decodedPayload = Base64.getUrlDecoder().decode(parts[1]);
        String payload = new String(decodedPayload, StandardCharsets.UTF_8);

        // Inject ADMIN role into payload
        String modifiedPayload = payload.substring(0, payload.length() - 1) + ",\"role\":\"ADMIN\"}";

        // Encode modified payload & keep original signature
        String encodedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(modifiedPayload.getBytes(StandardCharsets.UTF_8));

        String manipulatedToken = parts[0] + "." + encodedPayload + "." + parts[2];

        Response response = AuthEndpoints.adminRegister(createAdminPayload(), manipulatedToken);

        Assert.assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403,
                "Client must never gain Admin privileges. Actual: " + response.statusCode()
        );
    }
}