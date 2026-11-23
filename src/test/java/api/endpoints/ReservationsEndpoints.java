package api.endpoints;

import api.payloads.ReservationRequest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

public class ReservationsEndpoints {

    public static Response createReservation(ReservationRequest payload, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post(Routes.CREATE_RESERVATION);
    }

    public static Response cancelReservation(int id, String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .pathParam("id", id)
                .when()
                .put(Routes.CANCEL_RESERVATION);
    }

    public static Response getMyReservations(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(Routes.GET_MY_RESERVATIONS);
    }

    public static Response getAllReservations(String adminToken) {
        return given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(Routes.GET_ALL_RESERVATIONS);
    }
}