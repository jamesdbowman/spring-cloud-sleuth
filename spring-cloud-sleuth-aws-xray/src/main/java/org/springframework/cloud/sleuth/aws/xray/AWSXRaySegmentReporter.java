package org.springframework.cloud.sleuth.aws.xray;

import com.amazonaws.xray.entities.Segment;

/**
 * Contract for reporting AWS X-Ray segments to the X-Ray daemon.
 *
 * @author James Bowman
 * @since 1.2.1
 */
public interface AWSXRaySegmentReporter {
	/**
	 * Receives completed segments from {@link AWSXRaySpanListener} and submits them to... wait a second
	 */
	void report(Segment segment);
}
