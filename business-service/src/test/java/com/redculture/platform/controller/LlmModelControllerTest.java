package com.redculture.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmModelControllerTest {

    @Test
    void returnsSanitizedModelCatalogForAuthenticatedUser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/models", exchange -> {
            byte[] body = "{\"models\":[{\"id\":\"ernie\",\"displayName\":\"文心一言\",\"provider\":\"qianfan\",\"model\":\"ernie-test\",\"isDefault\":true}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AppMapProperties properties = new AppMapProperties();
            properties.setLlmServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            AgentRuntimeClient client = new AgentRuntimeClient(properties, new AgentProperties(), new ObjectMapper());
            LlmModelController controller = new LlmModelController(client);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, new AuthCurrentUserVO());

            var response = controller.list(request);

            assertEquals(200, response.getCode());
            assertEquals("ernie-test", response.getData().get(0).getModel());
        } finally {
            server.stop(0);
        }
    }
}
