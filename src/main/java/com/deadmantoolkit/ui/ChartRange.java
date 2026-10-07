package com.deadmantoolkit.ui;

import com.deadmantoolkit.MarketData;
import java.util.ArrayList;
import java.util.List;

/**
 * The price chart's time ranges and the bucket size requested for each. The chart always spans the whole range,
 * whatever data comes back.
 */
enum ChartRange
{
	H24("24h", "24 hours", 86_400, "5m", 300, 6),
	D7("7d", "7 days", 7 * 86_400, "1h", 3_600, 3),
	D30("30d", "30 days", 30 * 86_400, "6h", 21_600, 2),
	D90("90d", "90 days", 90 * 86_400, "6h", 21_600, 3),
	Y1("1y", "year", 365 * 86_400, "24h", 86_400, 3);

	static final ChartRange DEFAULT = D7;

	private final String label;
	private final String longLabel;
	private final long seconds;
	private final String step;
	private final long stepSeconds;
	private final int maxGapBuckets;

	ChartRange(String label, String longLabel, long seconds, String step, long stepSeconds, int maxGapBuckets)
	{
		this.label = label;
		this.longLabel = longLabel;
		this.seconds = seconds;
		this.step = step;
		this.stepSeconds = stepSeconds;
		this.maxGapBuckets = maxGapBuckets;
	}

	/** Button text, and the server's name for the range (the series request's range parameter). */
	String label()
	{
		return label;
	}

	/** For "No trades in the last ...". */
	String longLabel()
	{
		return longLabel;
	}

	long seconds()
	{
		return seconds;
	}

	/** The server's name for the bucket size requested with this range (the step parameter). */
	String step()
	{
		return step;
	}

	long stepSeconds()
	{
		return stepSeconds;
	}

	/** Two points further apart than this many buckets aren't joined by a line. */
	int maxGapBuckets()
	{
		return maxGapBuckets;
	}

	/**
	 * The buckets overlapping the range ending at {@code now} (unix s), in time order: a bucket that starts before
	 * the range but ends inside it is kept (its trades are in the range). Older servers ignore the range and send more
	 * history, so this always runs.
	 */
	List<MarketData.Point> trim(List<MarketData.Point> points, long now)
	{
		List<MarketData.Point> out = new ArrayList<>();
		if (points == null)
		{
			return out;
		}
		long from = now - seconds;
		for (MarketData.Point p : points)
		{
			if (p != null && p.getTimestamp() + stepSeconds() > from)
			{
				out.add(p);
			}
		}
		out.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
		return out;
	}
}
