package controllers;

import org.junit.Test;
import play.mvc.Http;
import play.mvc.Result;
import play.test.WithApplication;

import static org.junit.Assert.assertEquals;
import static play.test.Helpers.*;

public class LoanControllerTest extends WithApplication {

    @Test
    public void testLoanExceedingLimitIsRejected() {
        Http.RequestBuilder request = new Http.RequestBuilder()
                .method(POST)
                .uri("/api/loans/apply")
                .bodyJson(play.libs.Json.newObject()
                        .put("userId", 1)
                        .put("amount", 99999999.0) // Violates 3x contribution limit rule
                        .put("durationMonths", 6));

        Result result = route(app, request);
        assertEquals(BAD_REQUEST, result.status());
    }
}