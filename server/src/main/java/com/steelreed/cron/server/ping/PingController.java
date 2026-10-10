package com.steelreed.cron.server.ping;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import com.steelreed.cron.server.check.PingKind;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ping API of DESIGN.md §5, in the Healthchecks.io URL shape. No authentication: the ping
 * key is the secret.
 *
 * <p>The body is read straight from the request stream and {@code rid} from the raw query
 * string, so a form-encoded body ({@code curl -d}) is stored as text and not parsed as
 * parameters.
 */
@RestController
@RequestMapping(path = "/ping/{pingKey}", method = { RequestMethod.GET, RequestMethod.POST },
		produces = MediaType.TEXT_PLAIN_VALUE)
class PingController {

	private static final int MAX_RID_LENGTH = 64;

	private static final int MAX_USER_AGENT_LENGTH = 256;

	private static final int MAX_REMOTE_ADDR_LENGTH = 64;

	private final PingService pingService;

	PingController(PingService pingService) {
		this.pingService = pingService;
	}

	@RequestMapping
	ResponseEntity<String> success(@PathVariable String pingKey, HttpServletRequest request) throws IOException {
		return handle(pingKey, PingKind.SUCCESS, null, request);
	}

	@RequestMapping("/{action}")
	ResponseEntity<String> action(@PathVariable String pingKey, @PathVariable String action,
			HttpServletRequest request) throws IOException {
		return switch (action) {
			case "start" -> handle(pingKey, PingKind.START, null, request);
			case "fail" -> handle(pingKey, PingKind.FAIL, null, request);
			case "log" -> handle(pingKey, PingKind.LOG, null, request);
			default -> {
				Integer exitCode = parseExitCode(action);
				if (exitCode == null) {
					yield notFound();
				}
				yield handle(pingKey, (exitCode == 0) ? PingKind.SUCCESS : PingKind.FAIL, exitCode, request);
			}
		};
	}

	private ResponseEntity<String> handle(String pingKey, PingKind kind, Integer exitCode, HttpServletRequest request)
			throws IOException {
		UUID key = parseUuid(pingKey);
		if (key == null) {
			return notFound();
		}
		String body = readBody(request);
		PingService.Request ping = new PingService.Request(key, kind, exitCode,
				truncate(queryParameter(request.getQueryString(), "rid"), MAX_RID_LENGTH), body,
				truncate(request.getRemoteAddr(), MAX_REMOTE_ADDR_LENGTH),
				truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH));
		return switch (this.pingService.ping(ping)) {
			case OK -> ResponseEntity.ok("OK");
			case NOT_FOUND -> notFound();
			case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Rate limited");
		};
	}

	private static ResponseEntity<String> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found");
	}

	private static UUID parseUuid(String value) {
		try {
			UUID uuid = UUID.fromString(value);
			// UUID.fromString accepts short forms such as "1-2-3-4-5": require the canonical one.
			return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static Integer parseExitCode(String value) {
		if (value.isEmpty() || value.length() > 3 || !value.chars().allMatch(Character::isDigit)) {
			return null;
		}
		int code = Integer.parseInt(value);
		return (code <= 255) ? code : null;
	}

	/** Reads up to {@link PingService#MAX_BODY_BYTES} bytes as UTF-8; null when empty. */
	static String readBody(HttpServletRequest request) throws IOException {
		byte[] bytes;
		try (InputStream in = request.getInputStream()) {
			bytes = in.readNBytes(PingService.MAX_BODY_BYTES);
		}
		if (bytes.length == PingService.MAX_BODY_BYTES) {
			bytes = dropIncompleteTail(bytes);
		}
		return decode(bytes);
	}

	/** Cuts a UTF-8 sequence that truncation split in two, so it is not stored as garbage. */
	static byte[] dropIncompleteTail(byte[] bytes) {
		int i = bytes.length - 1;
		int continuation = 0;
		while (i >= 0 && continuation < 3 && (bytes[i] & 0xC0) == 0x80) {
			i--;
			continuation++;
		}
		if (i < 0) {
			return bytes;
		}
		int lead = bytes[i] & 0xFF;
		int expected = (lead >= 0xF0) ? 4 : (lead >= 0xE0) ? 3 : (lead >= 0xC0) ? 2 : 1;
		return (expected > continuation + 1) ? Arrays.copyOf(bytes, i) : bytes;
	}

	static String decode(byte[] bytes) {
		if (bytes.length == 0) {
			return null;
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
		String text;
		try {
			text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException ex) {
			throw new IllegalStateException(ex);
		}
		// PostgreSQL text cannot hold NUL characters.
		return text.replace("\u0000", "");
	}

	static String queryParameter(String query, String name) {
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = (eq >= 0) ? pair.substring(0, eq) : pair;
			if (key.equals(name)) {
				String value = (eq >= 0) ? pair.substring(eq + 1) : "";
				try {
					value = URLDecoder.decode(value, StandardCharsets.UTF_8);
				}
				catch (IllegalArgumentException ex) {
					return null;
				}
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

	private static String truncate(String value, int max) {
		return (value != null && value.length() > max) ? value.substring(0, max) : value;
	}

}
