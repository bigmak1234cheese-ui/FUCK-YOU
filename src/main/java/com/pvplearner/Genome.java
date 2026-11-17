package com.pvplearner;

import java.util.Properties;
import java.util.Random;

/** The combat settings the AI learns. Each value lives inside a fixed range. */
public final class Genome {
	public static final String[] NAMES = {
		"idealDist",     // melee: distance it tries to hold from the enemy
		"strafePeriod",  // ticks between changing strafe direction
		"minCharge",     // melee: weapon cooldown (0-1) it waits for before swinging
		"critPref",      // 0-1, how much it jumps and waits to land critical hits
		"aimError",      // degrees of aim noise (lower = more precise)
		"backoffDist",   // melee: retreats when the enemy is closer than this
		"crouchRate",    // 0-1, how often it taps crouch to dodge
		"drawTicks",     // bow: ticks it draws before releasing (20 = full power)
		"bowDist"        // bow: distance it tries to keep from the enemy
	};
	private static final double[][] RANGE = {
		{1.8, 3.0},
		{4, 40},
		{0.5, 1.0},
		{0, 1},
		{0, 8},
		{0.5, 2.0},
		{0, 1},
		{12, 24},
		{8, 20}
	};

	public final double[] v = new double[NAMES.length];

	public double idealDist()    { return v[0]; }
	public double strafePeriod() { return v[1]; }
	public double minCharge()    { return v[2]; }
	public double critPref()     { return v[3]; }
	public double aimError()     { return v[4]; }
	public double backoffDist()  { return v[5]; }
	public double crouchRate()   { return v[6]; }
	public double drawTicks()    { return v[7]; }
	public double bowDist()      { return v[8]; }

	public static Genome random(Random rng) {
		Genome g = new Genome();
		for (int i = 0; i < g.v.length; i++) {
			g.v[i] = RANGE[i][0] + rng.nextDouble() * (RANGE[i][1] - RANGE[i][0]);
		}
		return g;
	}

	public Genome mutate(Random rng, double sigma) {
		Genome g = new Genome();
		for (int i = 0; i < v.length; i++) {
			double span = RANGE[i][1] - RANGE[i][0];
			double nv = v[i] + rng.nextGaussian() * sigma * span;
			g.v[i] = Math.max(RANGE[i][0], Math.min(RANGE[i][1], nv));
		}
		return g;
	}

	public void write(Properties p, String prefix) {
		for (int i = 0; i < v.length; i++) {
			p.setProperty(prefix + NAMES[i], Double.toString(v[i]));
		}
	}

	/** Returns null if the saved style is missing values (for example it was saved by an older version). */
	public static Genome read(Properties p, String prefix) {
		Genome g = new Genome();
		for (int i = 0; i < g.v.length; i++) {
			String s = p.getProperty(prefix + NAMES[i]);
			if (s == null) return null;
			try {
				double d = Double.parseDouble(s);
				g.v[i] = Math.max(RANGE[i][0], Math.min(RANGE[i][1], d));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return g;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < v.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(NAMES[i]).append('=').append(String.format("%.2f", v[i]));
		}
		return sb.toString();
	}
}
