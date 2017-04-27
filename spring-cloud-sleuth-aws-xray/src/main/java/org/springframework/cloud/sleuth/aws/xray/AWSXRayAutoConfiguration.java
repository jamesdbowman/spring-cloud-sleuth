/*
 * Copyright 2013-2017 the original author or authors.
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

import java.net.SocketException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.sleuth.Sampler;
import org.springframework.cloud.sleuth.SpanAdjuster;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.cloud.sleuth.autoconfig.TraceAutoConfiguration;
import org.springframework.cloud.sleuth.metric.SpanMetricReporter;
import org.springframework.cloud.sleuth.sampler.PercentageBasedSampler;
import org.springframework.cloud.sleuth.sampler.SamplerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration Auto-configuration}
 * enables reporting to AWS X-Ray via the X-Ray daemon. Has a default {@link Sampler} set as
 * {@link PercentageBasedSampler}.
 *
 * @author James Bowman
 * @since 1.2.1
 *
 * @see PercentageBasedSampler
 * @see ZipkinRestTemplateCustomizer
 * @see DefaultZipkinRestTemplateCustomizer
 */
@Configuration
@EnableConfigurationProperties({AWSXRayProperties.class, SamplerProperties.class})
@ConditionalOnProperty(value = "spring.aws.xray.enabled", matchIfMissing = true)
@AutoConfigureBefore(TraceAutoConfiguration.class)
public class AWSXRayAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AWSXRaySegmentReporter reporter(SpanMetricReporter spanMetricReporter, AWSXRayProperties awsXRayProperties,
			AWSXRayRestTemplateCustomizer awsXRayRestTemplateCustomizer) throws SocketException {
		RestTemplate restTemplate = new RestTemplate();
		awsXRayRestTemplateCustomizer.customize(restTemplate);
		return new UDPAWSXRaySegmentReporter(restTemplate, awsXRayProperties.getBaseUrl());
	}

	@Bean
	@ConditionalOnMissingBean
	public AWSXRayRestTemplateCustomizer zipkinRestTemplateCustomizer(AWSXRayProperties awsXRayProperties) {
		return new DefaultAWSXRayRestTemplateCustomizer(awsXRayProperties);
	}

	@Bean
	@ConditionalOnMissingBean
	public Sampler defaultTraceSampler(SamplerProperties config) {
		return new PercentageBasedSampler(config);
	}

	@Bean
	public SpanReporter zipkinSpanListener(AWSXRaySegmentReporter reporter, EndpointLocator endpointLocator,
			Environment environment, SpanAdjuster spanAdjuster) {
		return new AWSXRaySegmentListener(reporter, endpointLocator, environment, spanAdjuster);
	}

	@Configuration
	@ConditionalOnMissingBean(EndpointLocator.class)
	@ConditionalOnProperty(value = "spring.zipkin.locator.discovery.enabled", havingValue = "false", matchIfMissing = true)
	protected static class DefaultEndpointLocatorConfiguration {

		@Autowired(required=false)
		private ServerProperties serverProperties;

		@Autowired
		private AWSXRayProperties awsXRayProperties;

		@Autowired(required=false)
		private InetUtils inetUtils;

		@Value("${spring.application.name:unknown}")
		private String appName;

		@Bean
		public EndpointLocator zipkinEndpointLocator() {
			return new ServerPropertiesEndpointLocator(this.serverProperties, this.appName,
					this.awsXRayProperties, this.inetUtils);
		}

	}

	@Configuration
	@ConditionalOnClass(DiscoveryClient.class)
	@ConditionalOnMissingBean(EndpointLocator.class)
	@ConditionalOnProperty(value = "spring.zipkin.locator.discovery.enabled", havingValue = "true")
	protected static class DiscoveryClientEndpointLocatorConfiguration {

		@Autowired(required=false)
		private ServerProperties serverProperties;

		@Autowired
		private AWSXRayProperties awsXRayProperties;

		@Autowired(required=false)
		private InetUtils inetUtils;

		@Value("${spring.application.name:unknown}")
		private String appName;

		@Autowired(required=false)
		private DiscoveryClient client;

		@Bean
		public EndpointLocator zipkinEndpointLocator() {
			return new FallbackHavingEndpointLocator(discoveryClientEndpointLocator(),
					new ServerPropertiesEndpointLocator(this.serverProperties, this.appName,
							this.awsXRayProperties, this.inetUtils));
		}

		private DiscoveryClientEndpointLocator discoveryClientEndpointLocator() {
			if (this.client!=null) {
				return new DiscoveryClientEndpointLocator(this.client, this.awsXRayProperties);
			}
			return null;
		}

	}

}
