/*
 * Copyright 2013-2015 the original author or authors.
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

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.cloud.commons.util.IdUtils;
import org.springframework.cloud.sleuth.Log;
import org.springframework.cloud.sleuth.NoOpSpanAdjuster;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanAdjuster;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Constants;
import zipkin.Endpoint;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.SegmentImpl;

/**
 * Listener of Sleuth events. Reports to Zipkin via {@link AWSXRaySegmentReporter}.
 *
 * @author Spencer Gibb
 * @since 1.0.0
 */
public class AWSXRaySegmentListener implements SpanReporter {
	private static final List<String> ZIPKIN_START_EVENTS = Arrays.asList(
			Constants.CLIENT_RECV, Constants.SERVER_RECV
	);
	private static final List<String> RPC_EVENTS = Arrays.asList(
			Constants.CLIENT_RECV, Constants.CLIENT_SEND, Constants.SERVER_RECV, Constants.SERVER_SEND
	);

	private static final org.apache.commons.logging.Log log = org.apache.commons.logging.LogFactory
			.getLog(AWSXRaySegmentListener.class);
	private static final Charset UTF_8 = Charset.forName("UTF-8");
	private static final byte[] UNKNOWN_BYTES = "unknown".getBytes(UTF_8);

	private final AWSXRaySegmentReporter reporter;
	private final Environment environment;
	private final SpanAdjuster spanAdjuster;
	/**
	 * Endpoint is the visible IP address of this service, the port it is listening on and
	 * the service name from discovery.
	 */
	// Visible for testing
	EndpointLocator endpointLocator;

	@Deprecated
	public AWSXRaySegmentListener(AWSXRaySegmentReporter reporter, EndpointLocator endpointLocator) {
		this(reporter, endpointLocator, null);
	}

	@Deprecated
	public AWSXRaySegmentListener(AWSXRaySegmentReporter reporter, EndpointLocator endpointLocator,
			Environment environment) {
		this(reporter, endpointLocator, environment, new NoOpSpanAdjuster());
	}

	public AWSXRaySegmentListener(AWSXRaySegmentReporter reporter, EndpointLocator endpointLocator,
			Environment environment, SpanAdjuster spanAdjuster) {
		this.reporter = reporter;
		this.endpointLocator = endpointLocator;
		this.environment = environment;
		this.spanAdjuster = spanAdjuster;
	}

	/**
	 * Converts a given Sleuth span to an AWS X-Ray segment.
	 * <ul>
	 * <li>Set ids, etc
	 * <li>Create timeline annotations based on data from Span object.
	 * <li>Create binary annotations based on data from Span object.
	 * </ul>
	 *
	 */
	// Visible for testing
	Segment convert(Span span) {
		//TODO: Consider adding support for the debug flag (related to #496)
		Span convertedSpan = this.spanAdjuster.adjust(span);
        Segment segment = new SegmentImpl(AWSXRay.getGlobalRecorder(), span.getName());
		Endpoint endpoint = this.endpointLocator.local();
		processLogs(convertedSpan, segment, endpoint);
		addZipkinAnnotations(segment, convertedSpan, endpoint);
		addZipkinBinaryAnnotations(segment, convertedSpan, endpoint);
		// In the RPC span model, the client owns the timestamp and duration of the span. If we
		// were propagated an id, we can assume that we shouldn't report timestamp or duration,
		// rather let the client do that. Worst case we were propagated an unreported ID and
		// Zipkin backfills timestamp and duration.
		if (!convertedSpan.isRemote()) {
            segment.setStartTime(convertedSpan.getBegin());
			/*if (!convertedSpan.isRunning()) { // duration is authoritative, only write when the span stopped
				zipkinSpan.duration(calculateDurationInMicros(convertedSpan));
			}*/
		}
        //segment.setTraceId(convertedSpan.getTraceId()); incompatible types..
		if (convertedSpan.getParents().size() > 0) {
			if (convertedSpan.getParents().size() > 1) {
				log.error("Zipkin doesn't support spans with multiple parents. Omitting "
						+ "other parents for " + convertedSpan);
			}
            segment.setParentId("" + convertedSpan.getParents().get(0));
		}
        //segment.setId("" + convertedSpan.getSpanId());
		return segment;
	}

	private void ensureLocalComponent(Span span, Segment segment, Endpoint localEndpoint) {
		if (span.tags().containsKey(Constants.LOCAL_COMPONENT)) {
			return;
		}
		byte[] processId = span.getProcessId() != null
				? span.getProcessId().toLowerCase().getBytes(UTF_8)
				: UNKNOWN_BYTES;
		BinaryAnnotation component = BinaryAnnotation.builder()
				.type(BinaryAnnotation.Type.STRING)
				.key("lc") // LOCAL_COMPONENT
				.value(processId)
				.endpoint(localEndpoint).build();
		//zipkinSpan.addBinaryAnnotation(component);
	}

	private void ensureServerAddr(Span span, Segment segment, Endpoint localEndpoint) {
		if (span.tags().containsKey(Span.SPAN_PEER_SERVICE_TAG_NAME)) {
			/*zipkinSpan.addBinaryAnnotation(BinaryAnnotation.address(Constants.SERVER_ADDR,
					localEndpoint.toBuilder().serviceName(
							span.tags().get(Span.SPAN_PEER_SERVICE_TAG_NAME)).build()));*/
		}
	}

	// Instead of going through the list of logs multiple times we're doing it only once
	private void processLogs(Span span, Segment segment, Endpoint endpoint) {
		boolean notClientOrServer = true;
		boolean hasClientSend = false;
		boolean instanceIdToTag = false;
		for (Log log : span.logs()) {
			if (RPC_EVENTS.contains(log.getEvent())) {
				instanceIdToTag = true;
			}
			if (ZIPKIN_START_EVENTS.contains(log.getEvent())) {
				notClientOrServer = false;
			}
			if (Constants.CLIENT_SEND.equals(log.getEvent())) {
				hasClientSend = !span.tags().containsKey(Constants.SERVER_ADDR);
			}
		}
		if (notClientOrServer) {
			// A zipkin span without any annotations cannot be queried, add special "lc" to avoid that.
			ensureLocalComponent(span, segment, endpoint);
		}
		if (hasClientSend) {
			ensureServerAddr(span, segment, endpoint);
		}
		if (instanceIdToTag && this.environment != null) {
			setInstanceIdIfPresent(segment, endpoint, Span.INSTANCEID);
		}
	}

	private void setInstanceIdIfPresent(Segment segment,
			Endpoint endpoint, String key) {
		String property = IdUtils.getDefaultInstanceId(this.environment);
		if (StringUtils.hasText(property)) {
			//addZipkinBinaryAnnotation(key, property, endpoint, zipkinSpan);
		}
	}

	/**
	 * Add annotations from the sleuth Span.
	 */
	private void addZipkinAnnotations(Segment segment,
			Span span, Endpoint endpoint) {
		for (Log ta : span.logs()) {
			Annotation zipkinAnnotation = Annotation.builder()
					.endpoint(endpoint)
					.timestamp(ta.getTimestamp() * 1000) // Zipkin is in microseconds
					.value(ta.getEvent()).build();
			//zipkinSpan.addAnnotation(zipkinAnnotation);
		}
	}

	/**
	 * Adds binary annotation from the sleuth Span
	 */
	private void addZipkinBinaryAnnotations(Segment segment,
			Span span, Endpoint ep) {
		for (Map.Entry<String, String> e : span.tags().entrySet()) {
			addZipkinBinaryAnnotation(e.getKey(), e.getValue(), ep, segment);
		}
	}

	private void addZipkinBinaryAnnotation(String key, String value, Endpoint ep,
			Segment segment) {
		BinaryAnnotation binaryAnn = BinaryAnnotation.builder()
				.type(BinaryAnnotation.Type.STRING)
				.key(key)
				.value(value.getBytes(UTF_8))
				.endpoint(ep).build();
		//zipkinSpan.addBinaryAnnotation(binaryAnn);
	}

	/**
	 * There could be instrumentation delay between span creation and the
	 * semantic start of the span (client send). When there's a difference,
	 * spans look confusing. Ex users expect duration to be client
	 * receive - send, but it is a little more than that. Rather than have
	 * to teach each user about the possibility of instrumentation overhead,
	 * we truncate absolute duration (span finish - create) to semantic
	 * duration (client receive - send)
	 */
	private long calculateDurationInMicros(Span span) {
		Log clientSend = hasLog(Span.CLIENT_SEND, span);
		Log clientReceived = hasLog(Span.CLIENT_RECV, span);
		if (clientSend != null && clientReceived != null) {
			return (clientReceived.getTimestamp() - clientSend.getTimestamp()) * 1000;
		}
		return span.getAccumulatedMicros();
	}

	private Log hasLog(String logName, Span span) {
		for (Log log : span.logs()) {
			if (logName.equals(log.getEvent())) {
				return log;
			}
		}
		return null;
	}

	@Override
	public void report(Span span) {
		if (span.isExportable()) {
			this.reporter.report(convert(span));
		} else {
			if (log.isDebugEnabled()) {
				log.debug("The span " + span + " will not be sent to Zipkin due to sampling");
			}
		}
	}
}
