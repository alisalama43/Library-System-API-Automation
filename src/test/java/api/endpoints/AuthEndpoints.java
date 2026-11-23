
package api.endpoints;

import api.payloads.LoginRequest;
import api.payloads.RegisterRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class AuthEndpoints {

    public static Response register(RegisterRequest payload) {

        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.REGISTER);
    }
    public static Response login(LoginRequest payload) {

        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.LOGIN);
    }
    public static Response adminRegister(RegisterRequest payload, String token) {

        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.ADMIN_REGISTER);
    }

}
