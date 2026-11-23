
package api.test.Auth;

import api.endpoints.AuthEndpoints;
import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import api.utilites.RestAssuredLogFilter;
import api.utilites.TestLogListener;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.testng.Assert;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

@Listeners(TestLogListener.class)
public class BaseTest {

    protected static final Logger logger =
            LogManager.getLogger(BaseTest.class);

    protected static String adminToken;
    protected static String clientToken;
    protected static String clientEmail;

    @BeforeSuite(alwaysRun = true)
    public void setupTokens() {

        // Base URL
        RestAssured.baseURI =
                "https://library-system-production-09db.up.railway.app";

        // Register REST Assured filters
        RestAssured.filters(
                new RestAssuredLogFilter(),
                new AllureRestAssured()
        );

        // ================= ADMIN LOGIN =================

        LoginRequest adminLogin =
                new LoginRequest("admin@library.com", "Admin@12345");

        Response adminRes = AuthEndpoints.login(adminLogin);

        logger.info("========== ADMIN LOGIN ==========");
        logger.info("Status Code: {}", adminRes.statusCode());
        logger.info("Response Body:\n{}", adminRes.asPrettyString());

        Assert.assertEquals(
                adminRes.statusCode(),
                200,
                "Admin Login Failed! Response: " + adminRes.asPrettyString()
        );

        adminToken = adminRes.jsonPath().getString("token");

        Assert.assertNotNull(adminToken, "Admin token is null!");

        // ================= CLIENT REGISTER =================

        clientEmail =
                "client_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest registerPayload =
                new RegisterRequest(
                        "Test Client",
                        clientEmail,
                        "secret123"
                );

        Response registerRes =
                AuthEndpoints.register(registerPayload);

        logger.info("========== CLIENT REGISTER ==========");
        logger.info("Status Code: {}", registerRes.statusCode());
        logger.info("Response Body:\n{}", registerRes.asPrettyString());

        Assert.assertEquals(
                registerRes.statusCode(),
                200,
                "Client Registration Failed!"
        );

        clientToken = registerRes.jsonPath().getString("token");

        Assert.assertNotNull(clientToken, "Client token is null!");
    }
}
