package com.pvplearner;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class PvpLearnerClient implements ClientModInitializer {
	public static final PvpBrain BRAIN = new PvpBrain();

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(BRAIN::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("pvpai")
			.then(ClientCommandManager.literal("start").executes(ctx -> {
				BRAIN.start(Minecraft.getInstance());
				return Command.SINGLE_SUCCESS;
			}))
			.then(ClientCommandManager.literal("stop").executes(ctx -> {
				BRAIN.stop(Minecraft.getInstance());
				return Command.SINGLE_SUCCESS;
			}))
			.then(ClientCommandManager.literal("status").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal("[PvP AI] " + BRAIN.status()));
				return Command.SINGLE_SUCCESS;
			}))
			.then(ClientCommandManager.literal("reset").executes(ctx -> {
				BRAIN.reset(Minecraft.getInstance());
				return Command.SINGLE_SUCCESS;
			}))
			.then(ClientCommandManager.literal("eval").executes(ctx -> {
				BRAIN.setLearning(false);
				BRAIN.start(Minecraft.getInstance());
				ctx.getSource().sendFeedback(Component.literal("[PvP AI] eval mode: using the best style, no experimenting"));
				return Command.SINGLE_SUCCESS;
			}))
			.then(ClientCommandManager.literal("learn")
				.then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(ctx -> {
					boolean on = BoolArgumentType.getBool(ctx, "on");
					BRAIN.setLearning(on);
					ctx.getSource().sendFeedback(Component.literal("[PvP AI] learning " + (on ? "on" : "off")));
					return Command.SINGLE_SUCCESS;
				}))));
	}
}
