package org.jmt.mcmt.asmdest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.config.GeneralConfig;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/* 1.15.2 code; AKA the only thing that changed  
import net.minecraft.world.biome.provider.SingleBiomeProviderSettings;
/* */

// TODO Should be renamed ChunkRepairHookTerminator (Note requres coremod edit)
/**
 * Handles chunk forcing in scenarios where world corruption has occured
 * 
 * @author jediminer543
 *
 */
public class DebugHookTerminator {
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	private static boolean bypassLoadTarget = false;
	
	public static class BrokenChunkLocator {
		long chunkPos;
		CompletableFuture<?> maincf;
		CompletableFuture<?> brokecf;
		public BrokenChunkLocator(long chunkPos, CompletableFuture<?> maincf, CompletableFuture<?> brokecf) {
			super();
			this.chunkPos = chunkPos;
			this.maincf = maincf;
			this.brokecf = brokecf;
		}
	}
	
	public static List<BrokenChunkLocator> breaks = new ArrayList<>();
	
	public static boolean isBypassLoadTarget() {
		return bypassLoadTarget;
	}
		
	public static void chunkLoadDrive(ServerChunkCache.MainThreadExecutor executor, BooleanSupplier isDone, ServerChunkCache scp, 
			CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completableFuture, long chunkpos) {
		if (!GeneralConfig.enableChunkTimeout) {
			bypassLoadTarget = false;
			executor.managedBlock(isDone);
			return;
		}
		int failcount = 0;
		while (!isDone.getAsBoolean()) {
			if (!executor.pollTask()) {
				if(isDone.getAsBoolean()) {
					break;
				}
				// Nothing more to execute
				if (failcount++ < GeneralConfig.timeoutCount) {
					Thread.yield();
					LockSupport.parkNanos("THE END IS ~~NEVER~~ LOADING", 100000L);
				} else {
					LOGGER.error("", new TimeoutException("Error fetching chunk " + chunkpos));	
					bypassLoadTarget = true;
					if (GeneralConfig.enableTimeoutRegen || GeneralConfig.enableBlankReturn) {
						if (GeneralConfig.enableBlankReturn) {
							Registry<Biome> biomeRegistry = scp.getLevel().registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
							LevelChunk out = new EmptyLevelChunk(scp.getLevel(), new ChunkPos(chunkpos), biomeRegistry.getHolder(0).get()); 
							completableFuture.complete(Either.left(out));
						} else {
							try {
								Optional<CompoundTag> cnbt = scp.chunkMap.read(new ChunkPos(chunkpos)).get();
								if (cnbt.isPresent()) {
									ProtoChunk cp = ChunkSerializer.read(scp.level, scp.getPoiManager(), new ChunkPos(chunkpos), cnbt.get());
									completableFuture.complete(Either.left(new LevelChunk(scp.level, cp, null)));
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
							completableFuture.complete(ChunkHolder.UNLOADED_CHUNK);
						}
					} else {
						System.err.println(completableFuture.toString());
						ChunkHolder chunkholder = scp.getVisibleChunkIfPresent(chunkpos);
						CompletableFuture<?> firstBroke = null;
						for (ChunkStatus cs : ChunkStatus.getStatusList()) {
							CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> cf = chunkholder.getFutureIfPresent(cs);
							if (cf == ChunkHolder.UNLOADED_CHUNK_FUTURE) {
								System.out.println("Status: " + cs.toString() + " is not yet loaded");
							} else {
								System.out.println("Status: " + cs.toString() + " is " + cf.toString());
								if (firstBroke == null && !cf.toString().contains("Completed normally")) {
									firstBroke = cf;
								}
							}
						}
						breaks.add(new BrokenChunkLocator(chunkpos, completableFuture, firstBroke));
						completableFuture.complete(Either.right(new ChunkHolder.ChunkLoadingFailure() {
							@Override
							public String toString() {
								return "TIMEOUT";
							}
						}));
					}
				}
			}
		}
	}
	
	public static void checkNull(Object o) {
		if (o == null) {
			System.out.println("Null warning:");
			new Throwable("Null trace").printStackTrace();
		}
	}
}
