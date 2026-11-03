package com.orderops.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

public record ApiSession(MockHttpSession session, String csrfToken) {
    public static ApiSession login(MockMvc mvc, ObjectMapper mapper, String username)
            throws Exception {
        var session = new MockHttpSession();
        var anonymousToken = token(mvc, mapper, session);
        var result =
                mvc.perform(
                                post("/login")
                                        .session(session)
                                        .param("username", username)
                                        .param("password", "test-" + username + "-password")
                                        .header("X-CSRF-TOKEN", anonymousToken))
                        .andExpect(status().isNoContent())
                        .andReturn();
        var authenticated = (MockHttpSession) result.getRequest().getSession(false);
        return new ApiSession(authenticated, token(mvc, mapper, authenticated));
    }

    private static String token(MockMvc mvc, ObjectMapper mapper, MockHttpSession session)
            throws Exception {
        var result =
                mvc.perform(get("/api/csrf").session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
