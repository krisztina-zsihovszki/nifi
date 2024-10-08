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
package org.apache.nifi.bootstrap.configuration;

/**
 * Enumeration of supported system properties for the application
 */
public enum SystemProperty {
    /** Path to application properties file */
    APPLICATION_PROPERTIES("nifi.properties.file.path"),

    /** Path to bootstrap configuration file */
    BOOTSTRAP_CONFIGURATION("org.apache.nifi.bootstrap.config.file"),

    /** Path to log directory */
    LOG_DIRECTORY("org.apache.nifi.bootstrap.config.log.dir"),

    /** Socket address and port number for management server */
    MANAGEMENT_SERVER_ADDRESS("org.apache.nifi.management.server.address");

    private final String property;

    SystemProperty(final String property) {
        this.property = property;
    }

    public String getProperty() {
        return property;
    }
}
