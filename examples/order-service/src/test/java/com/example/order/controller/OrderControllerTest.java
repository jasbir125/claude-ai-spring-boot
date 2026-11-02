package com.example.order.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.order.config.Correlation;
import com.example.order.config.SecurityConfig;
import com.example.order.dto.OrderLineResponse;
import com.example.order.dto.OrderResponse;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.exception.UserCannotOrderException;
import com.example.order.exception.UserCannotOrderException.Reason;
import com.example.order.model.CancellationReason;
import com.example.order.model.OrderStatus;
import com.example.order.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    private static final Instant AT = Instant.parse("2026-10-31T10:00:00Z");
    private static final OrderResponse ORDER = new OrderResponse(7L, 42L, OrderStatus.PLACED, "EUR",
            new BigDecimal("25.50"),
            List.of(new OrderLineResponse(1, "SKU-1", 2, new BigDecimal("10.00"), new BigDecimal("20.00")),
                    new OrderLineResponse(2, "SKU-2", 1, new BigDecimal("5.50"), new BigDecimal("5.50"))),
            AT, null, null, 0L);

    private static final String VALID_BODY = """
            {"userId": 42, "currency": "EUR",
             "lines": [{"sku": "SKU-1", "quantity": 2, "unitPrice": 10.00},
                       {"sku": "SKU-2", "quantity": 1, "unitPrice": 5.5}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void place_valid_returns201WithLocation_andAmountsAtCurrencyScale() throws Exception {
        when(orderService.place(any())).thenReturn(ORDER);

        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/orders/7")))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(content().string(containsString("\"totalAmount\":25.50")))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void place_invalidBody_returns400ProblemWithFieldCodes() throws Exception {
        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": -1, "currency": "eur",
                                 "lines": [{"sku": "", "quantity": 0, "unitPrice": -1}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'userId')].code").value("range"))
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')].code").value("currency"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].sku')].code").value("required"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].quantity')].code").value("range"))
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].unitPrice')].code").value("range"));

        verifyNoInteractions(orderService);
    }

    @Test
    void place_moreDecimalsThanCurrency_returns400Scale() throws Exception {
        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 42, "currency": "JPY",
                                 "lines": [{"sku": "SKU-1", "quantity": 1, "unitPrice": 100.5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].unitPrice"))
                .andExpect(jsonPath("$.errors[0].code").value("scale"));

        verifyNoInteractions(orderService);
    }

    @Test
    void place_noLines_returns400() throws Exception {
        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 42, "currency": "EUR", "lines": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines"))
                .andExpect(jsonPath("$.errors[0].code").value("size"));
    }

    @Test
    void place_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void place_userNotActive_returns422WithReason() throws Exception {
        when(orderService.place(any())).thenThrow(new UserCannotOrderException(42L, Reason.USER_NOT_ACTIVE));

        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().is(422))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("User cannot place orders"))
                .andExpect(jsonPath("$.reason").value("USER_NOT_ACTIVE"));
    }

    @Test
    void get_unknown_returns404Problem() throws Exception {
        when(orderService.findById(99L)).thenThrow(new OrderNotFoundException(99L));

        mvc.perform(get("/api/v1/orders/99").with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders/99"));
    }

    @Test
    void list_returnsPagedModel_andPassesFilters() throws Exception {
        when(orderService.findAll(eq(42L), eq(OrderStatus.PLACED), any()))
                .thenReturn(new PageImpl<>(List.of(ORDER), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v1/orders").param("userId", "42").param("status", "PLACED").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void list_unknownStatus_returns400() throws Exception {
        mvc.perform(get("/api/v1/orders").param("status", "SHIPPED").with(reader()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void cancel_returns200() throws Exception {
        var cancelled = new OrderResponse(7L, 42L, OrderStatus.CANCELLED, "EUR", ORDER.totalAmount(), ORDER.lines(),
                AT, CancellationReason.CUSTOMER_REQUEST, AT, 1L);
        when(orderService.cancel(7L)).thenReturn(cancelled);

        mvc.perform(post("/api/v1/orders/7/cancel").with(writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("CUSTOMER_REQUEST"));
    }

    @Test
    void cancel_concurrentModification_returns409() throws Exception {
        when(orderService.cancel(7L)).thenThrow(new ObjectOptimisticLockingFailureException("Order", 7L));

        mvc.perform(post("/api/v1/orders/7/cancel").with(writer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Version conflict"));
    }

    @Test
    void integrityViolation_returnsGeneric409_withoutSqlDetails() throws Exception {
        when(orderService.place(any())).thenThrow(new DataIntegrityViolationException("ERROR: secret SQL"));

        mvc.perform(post("/api/v1/orders").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The request conflicts with the current state"))
                .andExpect(content().string(not(containsString("secret SQL"))));
    }

    @Test
    void unexpectedError_returns500_withoutLeakingMessage() throws Exception {
        when(orderService.findById(1L)).thenThrow(new IllegalStateException("db password=hunter2"));

        mvc.perform(get("/api/v1/orders/1").with(reader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    void readScope_cannotWrite_returns403() throws Exception {
        mvc.perform(post("/api/v1/orders/7/cancel").with(reader()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void writeScopeOnly_cannotRead_returns403() throws Exception {
        mvc.perform(get("/api/v1/orders/7").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unmappedPath_isDenied() throws Exception {
        mvc.perform(get("/internal/debug").with(writer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void correlationId_isEchoed_andUnsafeValueReplaced() throws Exception {
        when(orderService.findAll(isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/orders").with(reader()).header(Correlation.HEADER, "checkout-123"))
                .andExpect(header().string(Correlation.HEADER, "checkout-123"));
        mvc.perform(get("/api/v1/orders").with(reader()).header(Correlation.HEADER, "bad value with spaces"))
                .andExpect(header().string(Correlation.HEADER, not("bad value with spaces")));
    }

    private static RequestPostProcessor reader() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read"));
    }

    private static RequestPostProcessor writer() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read"),
                new SimpleGrantedAuthority("SCOPE_orders.write"));
    }
}
