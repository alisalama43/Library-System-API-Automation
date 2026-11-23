package api.endpoints;

import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class ActivityEndpoints {

    public static Response getMyActivitySummary(String token) {
        return given()
                .header("Authorization", token != null && !token.isEmpty() ? "Bearer " + token : "")
                .when()
                .get(Routes.BASE_URL + "/api/users/me/activity-summary");
    }

    public static Response getUserActivitySummaryById(int userId, String token) {
        return given()
                .header("Authorization", token != null && !token.isEmpty() ? "Bearer " + token : "")
                .pathParam("id", userId)
                .when()
                .get(Routes.BASE_URL + "/api/users/{id}/activity-summary");
    }
}