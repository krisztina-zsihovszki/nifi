/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.client.impl.request;

import org.apache.nifi.registry.client.RequestConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestBasicAuthRequestConfig {

    @Test
    public void testBasicAuthRequestConfig() {
        final String username = "user1";
        final String password = "password";
        final String basicCreds = username + ":" + password;

        final String expectedHeaderValue = "Basic " + Base64.getEncoder().encodeToString(basicCreds.getBytes(StandardCharsets.UTF_8));

        final RequestConfig requestConfig = new BasicAuthRequestConfig(username, password);

        final Map<String, String> headers = requestConfig.getHeaders();
        assertNotNull(headers);
        assertEquals(1, headers.size());

        final String authorizationHeaderValue = headers.get("Authorization");
        assertEquals(expectedHeaderValue, authorizationHeaderValue);
    }
}
