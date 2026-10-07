package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class LogFilesTest
{
	@Test
	public void newestFirstSortsAcrossYearsWithLegacyLast()
	{
		assertEquals(Arrays.asList("trades-2026-01.jsonl", "trades-2025-12.jsonl", "trades-2025-02.jsonl", "trades.jsonl"),
			LogFiles.newestFirst(Arrays.asList("trades.jsonl", "trades-2025-02.jsonl", "trades-2026-01.jsonl",
				"trades-2025-12.jsonl")));
	}

	@Test
	public void unrelatedFilesAreIgnored()
	{
		assertEquals(Collections.singletonList("trades-2026-10.jsonl"),
			LogFiles.newestFirst(Arrays.asList("profit-abc.json", "trades-2026-10.jsonl", "trades-26-1.jsonl",
				"trades-2026-10.jsonl.bak", "Trades.jsonl", "trades-2026-1.jsonl", "notes.txt")));
	}

	@Test
	public void oldestFirstIsTheReverse()
	{
		assertEquals(Arrays.asList("trades.jsonl", "trades-2025-12.jsonl", "trades-2026-01.jsonl"),
			LogFiles.oldestFirst(Arrays.asList("trades-2026-01.jsonl", "trades.jsonl", "trades-2025-12.jsonl")));
	}

	@Test
	public void legacyOnly()
	{
		assertEquals(Collections.singletonList("trades.jsonl"), LogFiles.newestFirst(Collections.singletonList("trades.jsonl")));
		assertEquals(Collections.emptyList(), LogFiles.newestFirst(Collections.emptyList()));
	}

	@Test
	public void monthsOfFilesAndFilesFromAMonth()
	{
		assertEquals(java.time.YearMonth.of(2026, 7), LogFiles.monthOf("trades-2026-07.jsonl"));
		assertEquals(null, LogFiles.monthOf("trades.jsonl"));
		assertEquals(null, LogFiles.monthOf("trades-2026-13.jsonl"));
		assertEquals(null, LogFiles.monthOf(null));
		List<String> names = Arrays.asList("trades-2026-08.jsonl", "trades.jsonl", "trades-2026-06.jsonl", "trades-2026-07.jsonl",
			"notes.txt");
		assertEquals(Arrays.asList("trades.jsonl", "trades-2026-07.jsonl", "trades-2026-08.jsonl"),
			LogFiles.fromMonth(names, java.time.YearMonth.of(2026, 7)));
		assertEquals(LogFiles.oldestFirst(names), LogFiles.fromMonth(names, null));
	}
}
