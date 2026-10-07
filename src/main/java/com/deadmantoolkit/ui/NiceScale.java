package com.deadmantoolkit.ui;

/**
 * A price axis with "nice" round tick values (1, 2 or 5 times a power of ten apart), after Heckbert's "Nice numbers
 * for graph labels". Pure. Prices are whole gp, so the tick step is at least 1 and the axis never goes below 0.
 */
final class NiceScale
{
	/** Lowest and highest value on the axis; ticks start at min and end at max. */
	final long min;
	final long max;
	final long step;

	private NiceScale(long min, long max, long step)
	{
		this.min = min;
		this.max = max;
		this.step = step;
	}

	/**
	 * @param lo       lowest data value
	 * @param hi       highest data value
	 * @param maxTicks roughly how many ticks to aim for (at least 2)
	 */
	static NiceScale of(long lo, long hi, int maxTicks)
	{
		if (lo > hi)
		{
			long t = lo;
			lo = hi;
			hi = t;
		}
		lo = Math.max(0, lo);
		hi = Math.max(0, hi);
		if (lo == hi)
		{
			// A single point or flat data: give it some room above and below.
			long pad = Math.max(1, Math.round(hi * 0.05));
			lo = Math.max(0, lo - pad);
			hi = hi + pad;
		}
		int ticks = Math.max(2, maxTicks);
		double range = niceNum((double) (hi - lo), false);
		long step = Math.max(1, Math.round(niceNum(range / (ticks - 1), true)));
		long min = Math.floorDiv(lo, step) * step;
		long max = -Math.floorDiv(-hi, step) * step;
		if (max == min)
		{
			max = min + step;
		}
		return new NiceScale(Math.max(0, min), max, step);
	}

	/** Number of ticks, from {@link #min} to {@link #max} inclusive. */
	int tickCount()
	{
		return (int) ((max - min) / step) + 1;
	}

	long tick(int i)
	{
		return min + i * step;
	}

	/** A nice number near {@code x}: 1, 2 or 5 (or 10) times a power of ten; rounded, or else the next one up. */
	static double niceNum(double x, boolean round)
	{
		if (x <= 0)
		{
			return 1;
		}
		double exp = Math.floor(Math.log10(x));
		double pow = Math.pow(10, exp);
		double f = x / pow;
		double nf;
		if (round)
		{
			nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
		}
		else
		{
			nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
		}
		return nf * pow;
	}
}
