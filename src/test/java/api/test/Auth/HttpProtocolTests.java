
package api.test.Auth;

import api.endpoints.Routes;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;

public class HttpProtocolTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }

    private String loginBody() {
        return """
                {
                    "email": "admin@library.com",
                    "password": "Admin@12345"
                }
                """;
    }

    // 1. GET instead of POST
    @Test
    public void wrongHttpMethodTest() {

        Response response = given()
                .when()
                .get(Routes.LOGIN);

        System.out.println("GET Status: " + response.statusCode());

        Assert.assertEquals(
                response.statusCode(),
                405,
                "GET method should not be allowed for login"
        );
    }

    // 2. PUT instead of POST
    @Test
    public void wrongHttpMethodPutTest() {

        Response response = given()
                .when()
                .put(Routes.LOGIN);

        System.out.println("PUT Status: " + response.statusCode());

        Assert.assertEquals(
                response.statusCode(),
                405,
                "PUT method should not be allowed for login"
        );
    }

    // 3. Missing Content-Type
    @Test
    public void missingContentTypeTest() {

        Response response = given()
                .body(loginBody())
                .when()
                .post(Routes.LOGIN);

        System.out.println("Status: " + response.statusCode());
        System.out.println(response.asPrettyString());

        Assert.assertTrue(
                response.statusCode() == 400 ||
                        response.statusCode() == 415,
                "Unexpected response when Content-Type is missing: "
                        + response.statusCode()
        );
    }



    // 5. Correct Accept Header
    @Test
    public void acceptHeaderTest() {

        Response response = given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(loginBody())
                .when()
                .post(Routes.LOGIN);

        Assert.assertEquals(
                response.statusCode(),
                200,
                "Login should succeed with JSON request and response"
        );

        Assert.assertTrue(
                response.getContentType().contains("application/json"),
                "Response should be JSON"
        );
    }


    @Test
    public void optionsRequestTest() {

        Response response = given()
                .when()
                .options(Routes.LOGIN);

        System.out.println("OPTIONS Status: " + response.statusCode());
        System.out.println("Allow Header: " + response.getHeader("Allow"));

        Assert.assertTrue(
                response.statusCode() == 200 ||
                        response.statusCode() == 204 ||
                        response.statusCode() == 404 ||
                        response.statusCode() == 405,
                "Unexpected OPTIONS response: " + response.statusCode()
        );

        if (response.getHeader("Allow") != null) {
            Assert.assertFalse(
                    response.getHeader("Allow").isBlank(),
                    "Allow header should not be empty"
            );
        }
    }
}
