package com.srm.core.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestControllerTest {

    @Test
    void pingReturnsPongWithTimestamp() {
        TestController controller = new TestController();

        Map<String, Object> response = controller.ping();

        assertThat(response).containsEntry("code", 200);
        assertThat(response).containsEntry("message", "pong");
        assertThat(response).containsKey("timestamp");
    }
}
