package org.springframework.cloud.sleuth.aws.xray;

import java.net.SocketException;

import org.springframework.web.client.RestTemplate;

import com.amazonaws.xray.emitters.UDPEmitter;
import com.amazonaws.xray.entities.Segment;

/**
 * Submits AWS X-Ray segments using X-Ray's {@code UDPEmitter} class.
 *
 * @author James Bowman
 * @since 1.2.1
 */
public final class UDPAWSXRaySegmentReporter implements AWSXRaySegmentReporter {
	private final RestTemplateSender sender;
	private final UDPEmitter emitter;

    //TODO make this async like zipkin implementation

	/**
	 * @param restTemplate {@link RestTemplate} used for sending requests to Zipkin
	 * @param baseUrl       URL of the zipkin query server instance. Like: http://localhost:9411/
	 */
	public UDPAWSXRaySegmentReporter(RestTemplate restTemplate, String baseUrl) throws SocketException {
		this.sender = new RestTemplateSender(restTemplate, baseUrl);
        this.emitter = new UDPEmitter();
	}

	/**
	 * Emits the segment.
	 *
	 * @param segment X-Ray segment, should not be <code>null</code>.
	 */
	@Override
	public void report(Segment segment) {
		//this.emitter.report(span);
        this.emitter.sendSegment(segment);
	}

	/**
	 * Calling this will flush any pending spans to the http transport on the current thread.
	 */
	/*@Override
	public void flush() {
		this.delegate.flush();
	}*/

	/**
	 * Blocks until in-flight spans are sent and drops any that are left pending.
	 */
	/*@Override
	public void close() {
		this.delegate.close();
		this.sender.close();
	}*/
}
