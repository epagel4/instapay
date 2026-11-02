package com.instapay.payment.domain.limits;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Time windows a transfer at {@code now} counts against, in the account's local time zone: the
 * calendar day, and the night window (20:00-06:00, spanning midnight) if {@code now} is inside it.
 */
public record LimitWindows(Window day, Optional<Window> night) {

  public static final LocalTime NIGHT_START = LocalTime.of(20, 0);
  public static final LocalTime NIGHT_END = LocalTime.of(6, 0);

  /** Half-open interval [start, end). */
  public record Window(Instant start, Instant end) {}

  public LimitWindows {
    Objects.requireNonNull(day, "day");
    Objects.requireNonNull(night, "night");
  }

  public static LimitWindows at(Instant now, ZoneId zone) {
    ZonedDateTime local = now.atZone(zone);
    LocalDate date = local.toLocalDate();
    Window day =
        new Window(
            date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());

    LocalTime time = local.toLocalTime();
    Optional<LocalDate> nightStartDate =
        !time.isBefore(NIGHT_START)
            ? Optional.of(date)
            : time.isBefore(NIGHT_END) ? Optional.of(date.minusDays(1)) : Optional.empty();
    Optional<Window> night =
        nightStartDate.map(
            d ->
                new Window(
                    d.atTime(NIGHT_START).atZone(zone).toInstant(),
                    d.plusDays(1).atTime(NIGHT_END).atZone(zone).toInstant()));
    return new LimitWindows(day, night);
  }
}
