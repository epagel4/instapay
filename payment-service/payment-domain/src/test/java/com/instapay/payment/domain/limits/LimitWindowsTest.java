package com.instapay.payment.domain.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LimitWindowsTest {

  static final ZoneId BRT = ZoneId.of("America/Sao_Paulo"); // UTC-3, no DST since 2019

  @ParameterizedTest(name = "{0} local -> night window {1}")
  @CsvSource({
    "2026-11-04T10:00:00, ",
    "2026-11-04T19:59:59, ",
    "2026-11-04T20:00:00, 2026-11-04T20:00:00",
    "2026-11-04T23:59:59, 2026-11-04T20:00:00",
    "2026-11-05T00:00:00, 2026-11-04T20:00:00",
    "2026-11-05T05:59:59, 2026-11-04T20:00:00",
    "2026-11-05T06:00:00, "
  })
  void nightWindowSpansMidnight(String localNow, String expectedNightStartLocal) {
    LimitWindows w = LimitWindows.at(local(localNow), BRT);

    if (expectedNightStartLocal == null) {
      assertThat(w.night()).isEmpty();
    } else {
      Instant start = local(expectedNightStartLocal);
      assertThat(w.night())
          .hasValueSatisfying(
              n -> {
                assertThat(n.start()).isEqualTo(start);
                assertThat(n.end()).isEqualTo(start.plusSeconds(10 * 3600));
              });
    }
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-04T00:00:00, 2026-11-04T00:00:00",
    "2026-11-04T23:59:59, 2026-11-04T00:00:00",
    "2026-11-05T01:00:00, 2026-11-05T00:00:00"
  })
  void dayWindowIsTheLocalCalendarDay(String localNow, String expectedDayStartLocal) {
    LimitWindows w = LimitWindows.at(local(localNow), BRT);

    assertThat(w.day().start()).isEqualTo(local(expectedDayStartLocal));
    assertThat(w.day().end()).isEqualTo(local(expectedDayStartLocal).plusSeconds(24 * 3600));
  }

  @org.junit.jupiter.api.Test
  void usesLocalTimeNotUtc() {
    // 22:00 UTC is 19:00 in Sao Paulo: still daytime there.
    assertThat(LimitWindows.at(Instant.parse("2026-11-04T22:00:00Z"), BRT).night()).isEmpty();
    assertThat(LimitWindows.at(Instant.parse("2026-11-04T22:00:00Z"), ZoneId.of("UTC")).night())
        .isPresent();
  }

  static Instant local(String isoLocal) {
    return java.time.LocalDateTime.parse(isoLocal).atZone(BRT).toInstant();
  }
}
