package com.orderops.fulfillment;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OperationsController.class)
class OperationsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FulfillmentStore store;

    @Test
    void exposesOnlyAggregateOperationalCounts() throws Exception {
        given(store.metrics()).willReturn(new FulfillmentMetrics(2, 1, 3, 4, 5));
        mvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyJobs").value(2))
                .andExpect(jsonPath("$.runningJobs").value(1))
                .andExpect(jsonPath("$.failedJobs").value(3))
                .andExpect(jsonPath("$.unpublishedOutbox").value(4))
                .andExpect(jsonPath("$.shipments").value(5));
    }
}
