package com.orderops.orders.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.orders.ApiSession;
import com.orderops.orders.config.CsrfController;
import com.orderops.orders.config.JsonConfig;
import com.orderops.orders.config.SecurityConfig;
import com.orderops.orders.error.GlobalExceptionHandler;
import com.orderops.orders.inventory.InventoryController;
import com.orderops.orders.inventory.InventoryResponse;
import com.orderops.orders.inventory.InventoryService;

import jakarta.persistence.EntityNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

@WebMvcTest({
    OrderController.class,
    InventoryController.class,
    CsrfController.class,
    com.orderops.orders.config.SessionController.class
})
@Import({SecurityConfig.class, JsonConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(
        properties = {
            "orderops.security.operator-password=test-operator-password",
            "orderops.security.viewer-password=test-viewer-password"
        })
class OrderApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OrderApplicationService orders;
    @MockitoBean InventoryService inventory;

    @Test
    void replayedCreatesAndRetriesExposeTheirDistinctStatuses() throws Exception {
        var order = OrderLifecycleTest.confirmed();
        given(orders.create(any(), any(), any()))
                .willReturn(new OrderApplicationService.Mutation(OrderResponse.from(order), true));
        given(orders.retry(any(), any(), any()))
                .willReturn(new OrderApplicationService.Mutation(OrderResponse.from(order), false));
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingAddress.country").value("GB"))
                .andExpect(jsonPath("$.attemptGeneration").value(1))
                .andExpect(jsonPath("$.timeline[0].type").value("OrderConfirmed"));
        mvc.perform(
                        post("/api/orders/" + order.getId() + "/retry")
                                .session(login.session())
                                .header("Idempotency-Key", "retry-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken()))
                .andExpect(status().isAccepted());
        given(orders.retry(any(), any(), any()))
                .willReturn(new OrderApplicationService.Mutation(OrderResponse.from(order), true));
        mvc.perform(
                        post("/api/orders/" + order.getId() + "/retry")
                                .session(login.session())
                                .header("Idempotency-Key", "retry-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken()))
                .andExpect(status().isOk());
    }

    @Test
    void viewerCannotRetryAndMutationConflictsAre409() throws Exception {
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(
                        post("/api/orders/" + UUID.randomUUID() + "/retry")
                                .session(login.session())
                                .header("Idempotency-Key", "retry-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken()))
                .andExpect(status().isForbidden());
        given(orders.retry(any(), any(), any()))
                .willThrow(new OrderConflictException("Only failed fulfillment can be retried"));
        login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders/" + UUID.randomUUID() + "/retry")
                                .session(login.session())
                                .header("Idempotency-Key", "retry-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void paginatedReadsHaveAStableShapeAndRejectUnboundedSizes() throws Exception {
        given(orders.list(0, 20))
                .willReturn(
                        new OrderApplicationService.OrderPage(
                                List.of(OrderResponse.from(OrderLifecycleTest.confirmed())),
                                0,
                                20,
                                1,
                                1));
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(get("/api/orders").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").exists());
        mvc.perform(get("/api/orders?size=101").session(login.session()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders?page=-1").session(login.session()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "bad key with spaces", "with/unicode/é"})
    void invalidRequestKeysAreRejected(String key) throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", key)
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAddressAndUnknownDemoScenarioCannotCreateAnOrder() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"customerReference\":\"DEMO\",\"items\":[{\"sku\":\"COFFEE\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        validOrder()
                                                .replace(
                                                        "\"items\":",
                                                        "\"demoScenario\":\"UNKNOWN\",\"items\":")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "\"0\"", "true"})
    void demoScenarioMustBeANamedScenario(String scenario) throws Exception {
        given(orders.create(any(), any(), any()))
                .willReturn(
                        new OrderApplicationService.Mutation(
                                OrderResponse.from(OrderLifecycleTest.confirmed()), false));
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        validOrder()
                                                .replace(
                                                        "\"items\":",
                                                        "\"demoScenario\":"
                                                                + scenario
                                                                + ",\"items\":")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shippingAddressFieldsMustBeJsonStrings() throws Exception {
        given(orders.create(any(), any(), any()))
                .willReturn(
                        new OrderApplicationService.Mutation(
                                OrderResponse.from(OrderLifecycleTest.confirmed()), false));
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        validOrder()
                                                .replace(
                                                        "\"recipient\":\"Ada\"",
                                                        "\"recipient\":42")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingWithoutAnIdempotencyKeyIsRejected() throws Exception {
        given(orders.create(any(), any(), any()))
                .willReturn(
                        new OrderApplicationService.Mutation(
                                OrderResponse.from(
                                        Order.confirm(
                                                "CUSTOMER-42",
                                                OrderLifecycleTest.address(),
                                                DemoScenario.NORMAL,
                                                List.of(new OrderItem("COFFEE", 2)))),
                                false));
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSessionExposesTheAuthenticatedRoleAndLogoutReturnsNoContent() throws Exception {
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(get("/api/session").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("viewer"))
                .andExpect(jsonPath("$.role").value("VIEWER"));
        mvc.perform(
                        post("/logout")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void anOperatorCanCreateAndLocateAConfirmedOrder() throws Exception {
        var order =
                Order.confirm(
                        "CUSTOMER-42",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(new OrderItem("COFFEE", 2)));
        given(orders.create(any(), any(), any()))
                .willReturn(new OrderApplicationService.Mutation(OrderResponse.from(order), false));
        var login = ApiSession.login(mvc, mapper, "operator");

        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/" + order.getId()))
                .andExpect(jsonPath("$.customerReference").value("CUSTOMER-42"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void invalidQuantitiesAreRejectedAtTheHttpBoundary() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":0}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void aViewerCannotCreateOrders() throws Exception {
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorCannotWriteWithoutACsrfToken() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .contentType(APPLICATION_JSON)
                                .content(validOrder()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUsersCannotReadOrders() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrdersProduceAUsefulNotFoundResponse() throws Exception {
        var id = UUID.randomUUID();
        given(orders.get(id)).willThrow(new EntityNotFoundException("Order not found: " + id));
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(get("/api/orders/" + id).session(login.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aViewerCanInspectInventory() throws Exception {
        given(inventory.list()).willReturn(List.of(new InventoryResponse("COFFEE", "Coffee", 3)));
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(get("/api/inventory").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("COFFEE"))
                .andExpect(jsonPath("$[0].availableQuantity").value(3));
    }

    @Test
    void malformedOrderIdsReturnBadRequest() throws Exception {
        var login = ApiSession.login(mvc, mapper, "viewer");
        mvc.perform(get("/api/orders/not-a-uuid").session(login.session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullOrderLinesReturnValidationErrors() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[null]}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incorrectCredentialsReturnUnauthorizedInsteadOfAFalseSuccess() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var tokenResponse = mvc.perform(get("/api/csrf").session(session)).andReturn();
        var token =
                mapper.readTree(tokenResponse.getResponse().getContentAsString())
                        .get("token")
                        .asText();
        mvc.perform(
                        post("/login")
                                .session(session)
                                .header("X-CSRF-TOKEN", token)
                                .param("username", "operator")
                                .param("password", "incorrect"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "1000.9", "\"2\""})
    void quantitiesMustBeJsonIntegersWithoutCoercion(String quantity) throws Exception {
        var order =
                Order.confirm(
                        "CUSTOMER-42",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(new OrderItem("COFFEE", 2)));
        given(orders.create(any(), any(), any()))
                .willReturn(new OrderApplicationService.Mutation(OrderResponse.from(order), false));
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":%s}]}
                        """
                                                .formatted(quantity)))
                .andExpect(status().isBadRequest());
    }

    private String validOrder() {
        return """
                {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":2}]}
                """;
    }
}
