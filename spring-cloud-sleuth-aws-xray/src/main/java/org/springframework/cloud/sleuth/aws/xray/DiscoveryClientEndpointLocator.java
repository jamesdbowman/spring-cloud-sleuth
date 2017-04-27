/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.aws.xray;

import java.lang.invoke.MethodHandles;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.util.StringUtils;

import zipkin.Endpoint;

/**
 * An {@link EndpointLocator} that tries to find local service information from a
 * {@link DiscoveryClient}.
 *
 * You can override the name using {@link AWSXRayProperties.Service#setName(String)}
 *
 * @author James Bowman
 * @since 1.2.1
 */
public class DiscoveryClientEndpointLocator implements EndpointLocator {

	private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass());

	private final DiscoveryClient client;
	private final AWSXRayProperties awsXRayProperties;

	@Deprecated
	public DiscoveryClientEndpointLocator(DiscoveryClient client) {
		this(client, new AWSXRayProperties());
	}

	public DiscoveryClientEndpointLocator(DiscoveryClient client,
			AWSXRayProperties awsXRayProperties) {
		this.client = client;
		this.awsXRayProperties = awsXRayProperties;
	}

	@Override
	public Endpoint local() {
		ServiceInstance instance = this.client.getLocalServiceInstance();
		if (instance == null) {
			throw new NoServiceInstanceAvailableException();
		}
		String serviceName = StringUtils.hasText(this.awsXRayProperties.getService().getName()) ?
				this.awsXRayProperties.getService().getName() : instance.getServiceId();
		if (log.isDebugEnabled()) {
			log.debug("Span will contain serviceName [" + serviceName + "]");
		}
		return Endpoint.builder()
				.serviceName(serviceName)
				.ipv4(getIpAddress(instance))
				.port(instance.getPort()).build();
	}

	private int getIpAddress(ServiceInstance instance) {
		try {
			return InetUtils.getIpAddressAsInt(instance.getHost());
		}
		catch (Exception e) {
			return 0;
		}
	}

	static class NoServiceInstanceAvailableException extends RuntimeException { }
}
