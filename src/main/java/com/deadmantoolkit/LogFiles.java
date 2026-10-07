package com.deadmantoolkit;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which files in the plugin folder make up the local trade log, and in what order. Pure.
 * <p>
 * The log is one file per month, {@code trades-YYYY-MM.jsonl}, plus the legacy {@code trades.jsonl} written by older
 * versions, which only holds trades from before the monthly files existed (so it is always the oldest).
 */
final class LogFiles
{
	static final String LEGACY_FILE = "trades.jsonl";
	private static final Pattern MONTHLY = Pattern.compile("^trades-([0-9]{4})-([0-9]{2})[.]jsonl$");

	private LogFiles()
	{
	}

	static boolean isMonthly(String name)
	{
		return name != null && MONTHLY.matcher(name).matches();
	}

	/** The month of a monthly log file's name, or null for any other name. */
	static YearMonth monthOf(String name)
	{
		Matcher m = name == null ? null : MONTHLY.matcher(name);
		if (m == null || !m.matches())
		{
			return null;
		}
		int month = Integer.parseInt(m.group(2));
		return month >= 1 && month <= 12 ? YearMonth.of(Integer.parseInt(m.group(1)), month) : null;
	}

	/**
	 * The log files that can hold events from {@code from} on, oldest first: monthly files of that month or later,
	 * plus the legacy file (it has no month). All of them when {@code from} is null.
	 */
	static List<String> fromMonth(List<String> names, YearMonth from)
	{
		List<String> out = new ArrayList<>();
		for (String n : oldestFirst(names))
		{
			YearMonth m = monthOf(n);
			if (from == null || m == null || !m.isBefore(from))
			{
				out.add(n);
			}
		}
		return out;
	}

	/** Monthly log files, newest month first, then the legacy file if present. Other names are ignored. */
	static List<String> newestFirst(List<String> names)
	{
		List<String> monthly = new ArrayList<>();
		boolean legacy = false;
		for (String n : names)
		{
			if (isMonthly(n))
			{
				monthly.add(n);
			}
			else if (LEGACY_FILE.equals(n))
			{
				legacy = true;
			}
		}
		// Zero-padded year and month, so name order is time order.
		monthly.sort(Collections.reverseOrder());
		if (legacy)
		{
			monthly.add(LEGACY_FILE);
		}
		return monthly;
	}

	/** The reverse of {@link #newestFirst}: the legacy file first, then each month oldest first. */
	static List<String> oldestFirst(List<String> names)
	{
		List<String> list = newestFirst(names);
		Collections.reverse(list);
		return list;
	}
}
