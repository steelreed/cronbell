package com.steelreed.cron.server.ping;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PingControllerTests {

	@Test
	void queryParameterIsDecoded() {
		assertThat(PingController.queryParameter("a=1&rid=abc%2Fd", "rid")).isEqualTo("abc/d");
		assertThat(PingController.queryParameter("rid=", "rid")).isNull();
		assertThat(PingController.queryParameter("other=1", "rid")).isNull();
		assertThat(PingController.queryParameter(null, "rid")).isNull();
	}

	@Test
	void decodeRemovesNulCharacters() {
		assertThat(PingController.decode("a\u0000b".getBytes(StandardCharsets.UTF_8))).isEqualTo("ab");
		assertThat(PingController.decode(new byte[0])).isNull();
	}

	@Test
	void dropsASplitMultiByteCharacterAtTheEnd() {
		byte[] euro = "x€".getBytes(StandardCharsets.UTF_8); // 'x' + 3 bytes
		byte[] cut = java.util.Arrays.copyOf(euro, 3);
		assertThat(PingController.dropIncompleteTail(cut)).containsExactly('x');
		assertThat(PingController.dropIncompleteTail(euro)).isEqualTo(euro);
		byte[] ascii = "abc".getBytes(StandardCharsets.UTF_8);
		assertThat(PingController.dropIncompleteTail(ascii)).isEqualTo(ascii);
	}

}
