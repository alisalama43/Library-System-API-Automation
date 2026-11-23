package api.utilites;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RestAssuredLogFilter implements Filter {

    private static final Logger logger = LogManager.getLogger(RestAssuredLogFilter.class);

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        String authHeader = requestSpec.getHeaders().getValue("Authorization");
        String maskedAuth = (authHeader != null && authHeader.length() > 20)
                ? authHeader.substring(0, 20) + "...[MASKED]"
                : String.valueOf(authHeader);

        logger.info("==================== REQUEST ====================");
        logger.info("Endpoint : {} {}", requestSpec.getMethod(), requestSpec.getURI());
        logger.info("Auth     : {}", maskedAuth);
        if (requestSpec.getBody() != null) {
            logger.info("Body     : {}", (Object) requestSpec.getBody());
        }

        Response response = ctx.next(requestSpec, responseSpec);

        logger.info("==================== RESPONSE ===================");
        logger.info("Status   : {} ({} ms)", response.getStatusLine(), response.getTime());
        String resBody = response.getBody().asString();
        if (resBody != null && !resBody.isEmpty()) {
            logger.info("Body     : {}", resBody);
        }
        logger.info("=================================================\n");

        return response;
    }
}