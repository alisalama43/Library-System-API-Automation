package api.endpoints;

import api.payloads.BorrowRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class BorrowsEndpoints {

    public static Response createBorrow(BorrowRequest payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_BORROW);
    }

    public static Response returnBorrow(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .put(Routes.RETURN_BORROW);
    }

    public static Response getMyBorrows(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(Routes.GET_MY_BORROWS);
    }

    public static Response getBorrowById(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .get(Routes.GET_BORROW_BY_ID);
    }

    public static Response getAllBorrows(String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(Routes.GET_ALL_BORROWS);
    }
}