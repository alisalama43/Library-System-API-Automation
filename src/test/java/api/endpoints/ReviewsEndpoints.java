package api.endpoints;

import api.payloads.ReviewRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class ReviewsEndpoints {

    public static Response getReviewsByBookId(int bookId) {
        return given()
                .when()
                .get(Routes.GET_REVIEWS_BY_BOOK_ID, bookId);
    }

    public static Response getBookReviewsAverage(int bookId) {
        return given()
                .when()
                .get(Routes.GET_BOOK_REVIEWS_AVERAGE, bookId);
    }

    public static Response createReview(ReviewRequest payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_REVIEW);
    }

    public static Response updateReview(int id, ReviewRequest payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .pathParam("id", id)
                .body(payload)
                .when()
                .put(Routes.UPDATE_REVIEW);
    }

    public static Response deleteReview(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .delete(Routes.DELETE_REVIEW);
    }

    public static Response getMyReviews(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(Routes.GET_MY_REVIEWS);
    }
    public static Response createReview(Object payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_REVIEW);
    }
}