package com.deadmantoolkit.ui;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Time axis labels for the price chart, at round local times that suit the range: every few hours for 24h, days for
 * 7d, weeks for 30d and 90d, months for 1y. Pure.
 */
final class TimeTicks
{
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

	/** Tick times (unix s), ascending. */
	final long[] ts;
	final String[] labels;

	private TimeTicks(List<Long> ts, List<String> labels)
	{
		this.ts = new long[ts.size()];
		for (int i = 0; i < this.ts.length; i++)
		{
			this.ts[i] = ts.get(i);
		}
		this.labels = labels.toArray(new String[0]);
	}

	/** A label as wide as the widest this range uses, to work out how many fit. */
	static String sampleLabel(ChartRange range)
	{
		switch (range)
		{
			case H24:
				return "00:00";
			case D7:
				return "Wed";
			case Y1:
				return "May";
			default:
				return "30 May";
		}
	}

	/**
	 * @param t0        start of the axis (unix s)
	 * @param t1        end of the axis (unix s)
	 * @param zone      the player's time zone
	 * @param maxLabels most labels that fit; the finest spacing that fits is used, thinned out if none does
	 */
	static TimeTicks of(long t0, long t1, ChartRange range, ZoneId zone, int maxLabels)
	{
		List<Long> ts = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		if (maxLabels <= 0 || t1 <= t0)
		{
			return new TimeTicks(ts, labels);
		}
		int[] steps;
		switch (range)
		{
			case H24:
				steps = new int[]{3, 6, 12};
				break;
			case D7:
				steps = new int[]{1, 2};
				break;
			case D30:
				steps = new int[]{1, 2};
				break;
			case D90:
				steps = new int[]{2, 3, 4, 8};
				break;
			default:
				steps = new int[]{1, 2, 3, 6};
				break;
		}
		for (int i = 0; i < steps.length; i++)
		{
			ts.clear();
			labels.clear();
			generate(t0, t1, range, steps[i], zone, ts, labels);
			if (ts.size() <= maxLabels)
			{
				return new TimeTicks(ts, labels);
			}
		}
		// Even the coarsest spacing has too many: keep every k-th.
		int k = (ts.size() + maxLabels - 1) / maxLabels;
		List<Long> thinTs = new ArrayList<>();
		List<String> thinLabels = new ArrayList<>();
		for (int i = 0; i < ts.size(); i += k)
		{
			thinTs.add(ts.get(i));
			thinLabels.add(labels.get(i));
		}
		return new TimeTicks(thinTs, thinLabels);
	}

	private static void generate(long t0, long t1, ChartRange range, int n, ZoneId zone, List<Long> ts, List<String> labels)
	{
		ZonedDateTime start = Instant.ofEpochSecond(t0).atZone(zone);
		switch (range)
		{
			case H24:
			{
				// Every n hours on the local clock (00:00, 06:00, ...).
				ZonedDateTime z = start.truncatedTo(ChronoUnit.HOURS);
				for (; z.toEpochSecond() <= t1; z = z.plusHours(1))
				{
					if (z.toEpochSecond() >= t0 && z.getHour() % n == 0)
					{
						add(ts, labels, z, HOUR);
					}
				}
				break;
			}
			case D7:
			{
				// Local midnights, every n days.
				for (LocalDate d = start.toLocalDate(); ; d = d.plusDays(1))
				{
					ZonedDateTime z = d.atStartOfDay(zone);
					if (z.toEpochSecond() > t1)
					{
						break;
					}
					if (z.toEpochSecond() >= t0 && Math.floorMod(d.toEpochDay(), n) == 0)
					{
						add(ts, labels, z, WEEKDAY);
					}
				}
				break;
			}
			case D30:
			case D90:
			{
				// Monday midnights, every n weeks.
				for (LocalDate d = start.toLocalDate(); ; d = d.plusDays(1))
				{
					ZonedDateTime z = d.atStartOfDay(zone);
					if (z.toEpochSecond() > t1)
					{
						break;
					}
					// 1970-01-05 was the first Monday after the epoch.
					long week = Math.floorDiv(d.toEpochDay() - 4, 7);
					if (z.toEpochSecond() >= t0 && d.getDayOfWeek() == DayOfWeek.MONDAY && Math.floorMod(week, n) == 0)
					{
						add(ts, labels, z, DAY_MONTH);
					}
				}
				break;
			}
			default:
			{
				// The 1st of every n-th month.
				for (YearMonth m = YearMonth.from(start); ; m = m.plusMonths(1))
				{
					ZonedDateTime z = m.atDay(1).atStartOfDay(zone);
					if (z.toEpochSecond() > t1)
					{
						break;
					}
					if (z.toEpochSecond() >= t0 && Math.floorMod(m.getYear() * 12L + m.getMonthValue() - 1, n) == 0)
					{
						add(ts, labels, z, MONTH);
					}
				}
				break;
			}
		}
	}

	private static void add(List<Long> ts, List<String> labels, ZonedDateTime z, DateTimeFormatter f)
	{
		ts.add(z.toEpochSecond());
		labels.add(f.format(z));
	}
}
