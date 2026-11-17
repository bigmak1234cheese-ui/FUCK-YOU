package com.pvplearner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.Random;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the local player's controls to fight the nearest hostile mob or other player,
 * and learns its fighting style by trying mutated settings each fight and keeping what works.
 *
 * Only runs in single-player worlds. It refuses to run on multiplayer servers or LAN-opened worlds.
 */
public final class PvpBrain {
	private static final double SEARCH_RANGE = 16.0;
	private static final int EPISODE_MAX_TICKS = 20 * 30;
	private static final float MAX_TURN_PER_TICK = 45f;

	// reward shaping (score = damage dealt - damage taken + the bonuses below)
	private static final double KILL_BONUS = 10.0;
	private static final double DEATH_PENALTY = 10.0;
	private static final double SPEED_BONUS_MAX = 5.0;      // extra for a kill, shrinks the longer the fight takes
	private static final double SURVIVAL_PER_TICK = 0.005;  // small reward for staying alive (max ~3 per fight)
	private static final int ROLLING_FIGHTS = 20;            // window for the rolling win rate and average score

	private final Random rng = new Random();
	private final Path saveFile = FabricLoader.getInstance().getConfigDir().resolve("pvplearner.properties");
	private final Path logFile = FabricLoader.getInstance().getConfigDir().resolve("pvplearner_log.csv");
	private final Deque<double[]> recent = new ArrayDeque<>(); // each entry: {score, won (1/0)}

	// settings
	private boolean enabled = false;
	private boolean learning = true;

	// learner state
	private Genome best = Genome.random(rng);
	private double bestFitness = Double.NEGATIVE_INFINITY;
	private double sigma = 0.2;
	private int episodes = 0;
	private int wins = 0;
	private int losses = 0;

	// current fight
	private Genome current = best;
	private boolean inEpisode = false;
	private LivingEntity target;
	private float lastTargetHealth;
	private float lastSelfHealth;
	private double dealt;
	private double taken;
	private int ticks;
	private int sinceAttack = 99;
	private int strafeDir = 0;
	private int deadTicks = 0;
	private int crouchLeft = 0;
	private Vec3 lastTargetPos;

	public PvpBrain() {
		load();
	}

	// ---- control from commands ------------------------------------------------

	public boolean isEnabled() { return enabled; }
	public boolean isLearning() { return learning; }

	public void setLearning(boolean on) { learning = on; }

	public void start(Minecraft mc) {
		enabled = true;
		endFightQuietly();
		say(mc, "PvP AI on. Hold a weapon and get near a hostile mob.");
	}

	public void stop(Minecraft mc) {
		enabled = false;
		endFightQuietly();
		releaseKeys(mc);
		say(mc, "PvP AI off.");
	}

	public void reset(Minecraft mc) {
		best = Genome.random(rng);
		bestFitness = Double.NEGATIVE_INFINITY;
		sigma = 0.2;
		episodes = wins = losses = 0;
		recent.clear();
		save();
		say(mc, "Learning reset to a fresh random style.");
	}

	public String status() {
		return String.format("AI %s, learning %s | fights: %d (W %d / L %d) | %s | best score %s | style: %s",
			enabled ? "ON" : "off", learning ? "on" : "off", episodes, wins, losses, rollingSummary(),
			bestFitness == Double.NEGATIVE_INFINITY ? "n/a" : String.format("%.1f", bestFitness), best);
	}

	/** Win rate and average score over the last few fights, so you can see whether it is improving. */
	private String rollingSummary() {
		if (recent.isEmpty()) return "last fights: n/a";
		double score = 0;
		double won = 0;
		for (double[] r : recent) {
			score += r[0];
			won += r[1];
		}
		return String.format("last %d: %.0f%% wins, avg score %.1f", recent.size(), 100.0 * won / recent.size(), score / recent.size());
	}

	// ---- per-tick brain -------------------------------------------------------

	public void tick(Minecraft mc) {
		if (!enabled) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) return;

		if (!mc.isSingleplayer()) {
			enabled = false;
			endFightQuietly();
			releaseKeys(mc);
			say(mc, "PvP AI only runs in single-player worlds. Turned off.");
			return;
		}

		// dead: end the fight, respawn
		if (p.isDeadOrDying() || p.getHealth() <= 0) {
			if (inEpisode) endEpisode(mc, false, true);
			releaseKeys(mc);
			deadTicks++;
			if (deadTicks == 25) p.respawn();
			return;
		}
		deadTicks = 0;

		if (!inEpisode) {
			LivingEntity t = findTarget(mc, p);
			if (t == null) {
				releaseKeys(mc);
				return;
			}
			startEpisode(p, t);
		}

		// target gone or dead?
		if (target == null || target.isRemoved() || !target.isAlive() || target.getHealth() <= 0) {
			boolean killed = target != null && (target.getHealth() <= 0 || !target.isAlive());
			trackHealth(p);
			endEpisode(mc, killed, false);
			releaseKeys(mc);
			return;
		}

		ticks++;
		sinceAttack++;
		trackHealth(p);
		if (ticks > EPISODE_MAX_TICKS) {
			endEpisode(mc, false, false);
			releaseKeys(mc);
			return;
		}

		fight(mc, p);
	}

	private void fight(Minecraft mc, LocalPlayer p) {
		Genome g = current;
		var o = mc.options;
		double d = p.distanceTo(target);
		boolean bow = p.getMainHandItem().is(Items.BOW);

		// crouch taps: dodge by shrinking the hitbox, and crouching also stops sprinting
		if (crouchLeft > 0) {
			crouchLeft--;
		} else if (d < 6 && rng.nextDouble() < g.crouchRate() * 0.04) {
			crouchLeft = 3 + rng.nextInt(6);
		}
		boolean crouching = crouchLeft > 0;
		o.keyShift.setDown(crouching);

		// where to aim (yaw) and at what angle (pitch)
		Vec3 eye = p.getEyePosition();
		double noise = Math.tan(Math.toRadians(g.aimError())) * d;
		double nx = rng.nextGaussian() * noise * 0.5;
		double ny = rng.nextGaussian() * noise * 0.5;
		double nz = rng.nextGaussian() * noise * 0.5;
		float wantYaw;
		float wantPitch;
		if (bow) {
			double speed = 3.0 * drawPower(g.drawTicks());
			Vec3 vel = lastTargetPos == null ? Vec3.ZERO
				: new Vec3(target.getX() - lastTargetPos.x, 0, target.getZ() - lastTargetPos.z);
			Vec3 base = target.position().add(0, target.getBbHeight() * 0.6, 0);
			Vec3 pred = base;
			for (int i = 0; i < 3; i++) {
				double flight = pred.subtract(eye).length() / Math.max(1.0, speed * 0.9);
				pred = base.add(vel.scale(flight));
			}
			double dx = pred.x + nx - eye.x;
			double dy = pred.y + ny - eye.y;
			double dz = pred.z + nz - eye.z;
			double horiz = Math.sqrt(dx * dx + dz * dz);
			wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			wantPitch = (float) -solveElevation(speed, horiz, dy);
		} else {
			Vec3 aim = target.position().add(nx, target.getBbHeight() * 0.8 + ny, nz);
			double dx = aim.x - eye.x;
			double dy = aim.y - eye.y;
			double dz = aim.z - eye.z;
			double horiz = Math.sqrt(dx * dx + dz * dz);
			wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		}
		lastTargetPos = target.position();

		// turn toward it at a limited speed
		float turnYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
		turnYaw = Math.max(-MAX_TURN_PER_TICK, Math.min(MAX_TURN_PER_TICK, turnYaw));
		float turnPitch = wantPitch - p.getXRot();
		turnPitch = Math.max(-MAX_TURN_PER_TICK, Math.min(MAX_TURN_PER_TICK, turnPitch));
		p.setYRot(p.getYRot() + turnYaw);
		p.setXRot(Mth.clamp(p.getXRot() + turnPitch, -90f, 90f));

		// strafing (both modes)
		if (ticks % Math.max(1, (int) Math.round(g.strafePeriod())) == 0) {
			strafeDir = rng.nextInt(3) - 1;
		}
		o.keyLeft.setDown(strafeDir == -1);
		o.keyRight.setDown(strafeDir == 1);

		if (bow) {
			boolean los = p.hasLineOfSight(target);
			double want = g.bowDist();
			o.keyUp.setDown(d > want + 4 || !los);
			o.keyDown.setDown(d < Math.max(3.0, want - 4));
			o.keySprint.setDown(false);
			o.keyJump.setDown(p.horizontalCollision);
			// draw, release when charged, then draw again
			if (los && d < 45) {
				boolean charged = p.isUsingItem() && p.getTicksUsingItem() >= (int) Math.round(g.drawTicks());
				o.keyUse.setDown(!charged);
			} else {
				o.keyUse.setDown(false);
			}
			return;
		}

		// melee movement
		o.keyUse.setDown(false);
		o.keyUp.setDown(d > g.idealDist());
		o.keyDown.setDown(d < g.backoffDist());
		o.keySprint.setDown(!crouching && d > g.idealDist() + 0.4);
		boolean wantCritJump = !crouching && p.onGround() && d < 4.5 && rng.nextDouble() < g.critPref() * 0.1;
		o.keyJump.setDown(wantCritJump || p.horizontalCollision);

		// melee attack: wait for cooldown, and for a falling hit if it likes crits
		if (d < 3.0 && p.getAttackStrengthScale(0.5f) >= g.minCharge()) {
			boolean falling = !p.onGround() && p.getDeltaMovement().y < 0;
			if (g.critPref() < 0.5 || falling || sinceAttack > 20) {
				mc.gameMode.attack(p, target);
				p.swing(InteractionHand.MAIN_HAND);
				sinceAttack = 0;
			}
		}
	}

	/** Vanilla bow power for a given number of draw ticks (0..1). */
	private static double drawPower(double drawTicks) {
		double f = Math.min(1.0, drawTicks / 20.0);
		return Math.min(1.0, (f * f + f * 2.0) / 3.0);
	}

	/**
	 * Finds the launch angle (degrees above horizontal) that makes an arrow land at the given
	 * horizontal distance and height difference, by simulating arrow flight (drag 0.99, gravity 0.05).
	 */
	private static double solveElevation(double speed, double horiz, double dy) {
		double bestAngle = 0;
		double bestErr = Double.MAX_VALUE;
		for (double a = -45; a <= 60; a += 0.25) {
			double rad = Math.toRadians(a);
			double x = 0, y = 0;
			double vx = speed * Math.cos(rad);
			double vy = speed * Math.sin(rad);
			double hitY = Double.NaN;
			for (int t = 0; t < 160; t++) {
				double nx = x + vx;
				double ny = y + vy;
				if (nx >= horiz) {
					double f = vx > 0 ? (horiz - x) / vx : 0;
					hitY = y + vy * f;
					break;
				}
				x = nx;
				y = ny;
				vx *= 0.99;
				vy *= 0.99;
				vy -= 0.05;
			}
			if (Double.isNaN(hitY)) continue;
			double err = Math.abs(hitY - dy);
			if (err < bestErr) {
				bestErr = err;
				bestAngle = a;
			}
		}
		return bestAngle;
	}

	// ---- fights and learning --------------------------------------------------

	private LivingEntity findTarget(Minecraft mc, LocalPlayer p) {
		LivingEntity bestT = null;
		double bestD = Double.MAX_VALUE;
		var box = p.getBoundingBox().inflate(SEARCH_RANGE);
		for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box, e -> isValidTarget(p, e))) {
			double dist = p.distanceToSqr(e);
			if (dist < bestD) {
				bestD = dist;
				bestT = e;
			}
		}
		return bestT;
	}

	private static boolean isValidTarget(LocalPlayer p, LivingEntity e) {
		if (e == p || !e.isAlive() || e.isSpectator()) return false;
		return e instanceof Enemy || e instanceof Player;
	}

	private void startEpisode(LocalPlayer p, LivingEntity t) {
		target = t;
		current = learning ? best.mutate(rng, sigma) : best;
		inEpisode = true;
		dealt = 0;
		taken = 0;
		ticks = 0;
		sinceAttack = 99;
		strafeDir = 0;
		crouchLeft = 0;
		lastTargetPos = null;
		lastTargetHealth = t.getHealth();
		lastSelfHealth = p.getHealth();
	}

	private void trackHealth(LocalPlayer p) {
		if (target != null) {
			float th = target.getHealth();
			if (th < lastTargetHealth) dealt += lastTargetHealth - th;
			lastTargetHealth = th;
		}
		float sh = p.getHealth();
		if (sh < lastSelfHealth) taken += lastSelfHealth - sh;
		lastSelfHealth = sh;
	}

	private void endEpisode(Minecraft mc, boolean won, boolean died) {
		if (!inEpisode) return;
		double speedBonus = won ? SPEED_BONUS_MAX * Math.max(0.0, 1.0 - (double) ticks / EPISODE_MAX_TICKS) : 0;
		double survival = died ? 0 : ticks * SURVIVAL_PER_TICK;
		double fitness = dealt - taken + (won ? KILL_BONUS : 0) - (died ? DEATH_PENALTY : 0) + speedBonus + survival;
		episodes++;
		if (won) wins++;
		if (died) losses++;
		recent.addLast(new double[] {fitness, won ? 1 : 0});
		while (recent.size() > ROLLING_FIGHTS) recent.removeFirst();
		logFight(fitness, won, died);

		String note = "";
		if (learning) {
			if (fitness > bestFitness) {
				best = current;
				bestFitness = fitness;
				sigma = Math.min(0.3, sigma * 1.2);
				note = " | new best style";
				save();
			} else {
				sigma = Math.max(0.03, sigma * 0.95);
				bestFitness -= 0.5; // let a lucky old score fade so styles keep getting re-tested
			}
		}
		say(mc, String.format("Fight %d: %s | dealt %.1f, took %.1f, score %.1f%s",
			episodes, died ? "died" : won ? "won" : "ended", dealt, taken, fitness, note));
		endFightQuietly();
	}

	/** Appends one line per fight to config/pvplearner_log.csv (open it in a spreadsheet to graph progress). */
	private void logFight(double fitness, boolean won, boolean died) {
		try {
			StringBuilder sb = new StringBuilder();
			if (!Files.exists(logFile)) {
				sb.append("fight,result,ticks,dealt,taken,score,learning,").append(String.join(",", Genome.NAMES)).append('\n');
			}
			sb.append(episodes).append(',').append(died ? "died" : won ? "won" : "ended").append(',').append(ticks)
				.append(',').append(String.format("%.2f,%.2f,%.2f", dealt, taken, fitness)).append(',').append(learning);
			for (double x : current.v) sb.append(',').append(String.format("%.3f", x));
			sb.append('\n');
			Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// not fatal: the log is only for your own review
		}
	}

	private void endFightQuietly() {
		inEpisode = false;
		target = null;
	}

	// ---- helpers --------------------------------------------------------------

	private static void releaseKeys(Minecraft mc) {
		if (mc.options == null) return;
		var o = mc.options;
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyJump.setDown(false);
		o.keySprint.setDown(false);
		o.keyShift.setDown(false);
		o.keyUse.setDown(false);
	}

	private static void say(Minecraft mc, String msg) {
		if (mc.gui != null) {
			mc.gui.getChat().addMessage(Component.literal("[PvP AI] " + msg));
		}
	}

	private void save() {
		Properties p = new Properties();
		best.write(p, "best.");
		p.setProperty("bestFitness", Double.toString(bestFitness));
		p.setProperty("sigma", Double.toString(sigma));
		p.setProperty("episodes", Integer.toString(episodes));
		p.setProperty("wins", Integer.toString(wins));
		p.setProperty("losses", Integer.toString(losses));
		try (OutputStream out = Files.newOutputStream(saveFile)) {
			p.store(out, "PvP Learner saved style");
		} catch (IOException e) {
			// not fatal: it just won't remember between sessions
		}
	}

	private void load() {
		if (!Files.exists(saveFile)) return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(saveFile)) {
			p.load(in);
			Genome g = Genome.read(p, "best.");
			if (g != null) best = g;
			bestFitness = Double.parseDouble(p.getProperty("bestFitness", "-Infinity"));
			sigma = Double.parseDouble(p.getProperty("sigma", "0.2"));
			episodes = Integer.parseInt(p.getProperty("episodes", "0"));
			wins = Integer.parseInt(p.getProperty("wins", "0"));
			losses = Integer.parseInt(p.getProperty("losses", "0"));
		} catch (IOException | NumberFormatException e) {
			// bad or unreadable save: start fresh
		}
	}
}
