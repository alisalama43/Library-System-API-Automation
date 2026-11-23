
package api.test.Auth;

import api.endpoints.Routes;
import api.utilites.RestAssuredLogFilter;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;

public class JwtAdvancedSecurityTests extends BaseTest {
    @BeforeClass(alwaysRun = true)
    public void initFilter() {
        if (RestAssured.filters().isEmpty()) {
            RestAssured.filters(new RestAssuredLogFilter());
        }
    }
    private static final String FAKE_SECRET =
            "ThisIsNotTheRealServerSecret123";

    private static final String ADMIN_BODY = """
        {
          "fullName": "Security Test Admin",
          "email": "jwt.security.test@example.com",
          "password": "Admin@12345"
        }
        """;

    private Response sendToken(String token) {
        return given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(ADMIN_BODY)
                .when()
                .post(Routes.ADMIN_REGISTER);
    }

    private String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        value.getBytes(StandardCharsets.UTF_8)
                );
    }

    private String createSignedToken(
            String header,
            String payload,
            String secret
    ) throws Exception {

        String unsignedToken =
                encode(header) + "." + encode(payload);

        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        byte[] signature = mac.doFinal(
                unsignedToken.getBytes(StandardCharsets.UTF_8)
        );

        return unsignedToken + "." +
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(signature);
    }


    @Test
    public void algNoneAttackTest() {

        String header = """
            {"alg":"none","typ":"JWT"}
            """;

        String payload = """
            {"sub":"admin@library.com","role":"ADMIN"}
            """;

        String token = encode(header) + "." +
                encode(payload) + ".";

        Response response = sendToken(token);

        Assert.assertEquals(
                response.statusCode(), 403,
                "Unsigned JWT should be rejected"
        );
    }

    // 2. JWT Claim Injection
    @Test
    public void claimInjectionTest() throws Exception {

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"client@library.com",
              "role":"ADMIN",
              "isAdmin":true
            }
            """;

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 3. Missing exp Claim
    @Test
    public void missingExpirationClaimTest() throws Exception {

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "iss":"library-api",
              "aud":"library-client"
            }
            """;

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 4. Future nbf Claim
    @Test
    public void futureNotBeforeClaimTest() throws Exception {

        long futureTime =
                System.currentTimeMillis() / 1000 + 3600;

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "nbf":%d,
              "exp":%d
            }
            """.formatted(futureTime, futureTime + 7200);

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 5. Invalid Issuer
    @Test
    public void invalidIssuerTest() throws Exception {

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "iss":"attacker-server",
              "exp":%d
            }
            """.formatted(
                System.currentTimeMillis() / 1000 + 3600
        );

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 6. Invalid Audience
    @Test
    public void invalidAudienceTest() throws Exception {

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "aud":"attacker-application",
              "exp":%d
            }
            """.formatted(
                System.currentTimeMillis() / 1000 + 3600
        );

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 7. Wrong Signing Key
    @Test
    public void wrongSigningKeyTest() throws Exception {

        String header =
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "exp":%d
            }
            """.formatted(
                System.currentTimeMillis() / 1000 + 3600
        );

        String token = createSignedToken(
                header, payload, "WrongSigningKey123"
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }

    // 8. JWT Header Injection
    @Test
    public void jwtHeaderInjectionTest() throws Exception {

        String header = """
            {
              "alg":"HS256",
              "typ":"JWT",
              "kid":"../../../../attacker/key"
            }
            """;

        String payload = """
            {
              "sub":"admin@library.com",
              "role":"ADMIN",
              "exp":%d
            }
            """.formatted(
                System.currentTimeMillis() / 1000 + 3600
        );

        String token = createSignedToken(
                header, payload, FAKE_SECRET
        );

        Response response = sendToken(token);

        Assert.assertEquals(response.statusCode(), 403);
    }
}
