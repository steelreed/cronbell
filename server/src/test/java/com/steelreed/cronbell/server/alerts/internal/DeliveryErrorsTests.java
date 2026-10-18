package com.steelreed.cronbell.server.alerts.internal;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DeliveryErrorsTests {

	private static final Duration CONNECT = Duration.ofSeconds(5);

	private static final Duration READ = Duration.ofSeconds(10);

	private final HttpPoster poster = new HttpPoster(
			new NotifierProperties(Duration.ofSeconds(5), CONNECT, Duration.ofMillis(300), "https://api.telegram.org"));

	@Test
	void unknownHost() {
		assertThat(DeliveryErrors.network(new IOException(new UnresolvedAddressException()), "hooks.example.invalid",
				443, CONNECT, READ))
			.isEqualTo("Could not resolve hooks.example.invalid");
	}

	@Test
	void refusedConnection() {
		assertThat(DeliveryErrors.network(new ConnectException(), "ntfy.internal", 8080, CONNECT, READ))
			.isEqualTo("Connection refused by ntfy.internal:8080");
		assertThat(
				DeliveryErrors.network(new ConnectException("No route to host"), "ntfy.internal", 8080, CONNECT, READ))
			.isEqualTo("Could not connect to ntfy.internal:8080: No route to host");
	}

	@Test
	void timeouts() {
		assertThat(DeliveryErrors.network(new HttpConnectTimeoutException("HTTP connect timed out"),
				"hooks.example.com", 443, CONNECT, READ))
			.isEqualTo("Timed out connecting to hooks.example.com:443 after 5 s");
		assertThat(DeliveryErrors.network(new HttpTimeoutException("request timed out"), "hooks.example.com", 443,
				CONNECT, READ))
			.isEqualTo("Timed out after 10 s waiting for hooks.example.com");
	}

	@Test
	void tlsAndOtherErrors() {
		assertThat(DeliveryErrors.network(new IOException(new SSLHandshakeException("PKIX path building failed")),
				"hooks.example.com", 443, CONNECT, READ))
			.isEqualTo("TLS handshake with hooks.example.com:443 failed");
		assertThat(DeliveryErrors.network(new IOException("Connection reset"), "hooks.example.com", 443, CONNECT, READ))
			.isEqualTo("Request to hooks.example.com:443 failed: Connection reset");
		assertThat(DeliveryErrors.withReason("Could not build the email", new IllegalStateException()))
			.isEqualTo("Could not build the email");
	}

	@Test
	void postToAClosedPortIsRefused() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		URI uri = URI.create("http://localhost:" + port + "/hook/secret-path");
		assertThatExceptionOfType(DeliveryException.class)
			.isThrownBy(() -> this.poster.post(uri, Map.of(), new byte[0]))
			.satisfies((ex) -> assertThat(ex.retryable()).isTrue())
			.withMessage("Connection refused by localhost:" + port);
	}

	@Test
	void postToAnUnresolvableHost() {
		assertThatExceptionOfType(DeliveryException.class).isThrownBy(
				() -> this.poster.post(URI.create("https://hooks.example.invalid/secret-path"), Map.of(), new byte[0]))
			.withMessage("Could not resolve hooks.example.invalid");
	}

	@Test
	void postToAHostThatNeverAnswers() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			URI uri = URI.create("http://localhost:" + socket.getLocalPort() + "/hook");
			assertThatExceptionOfType(DeliveryException.class)
				.isThrownBy(() -> this.poster.post(uri, Map.of(), new byte[0]))
				.withMessage("Timed out after 300 ms waiting for localhost");
		}
	}

}
