package com.aicivilization.profession;

import com.aicivilization.entity.CitizenEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Common contract for autonomous nation professions (Lumberjack, Storager, Miner, etc.).
 * Ensures clean modularity, isolation of duties, and easy scalability for new jobs.
 */
public interface ProfessionJob {
    /** Unique identifier for serialization and registry (e.g. "lumberjack", "storage", "miner"). */
    String getId();

    /** Display label shown in the NPC name tag (e.g. "LumberJack", "Storager", "Miner"). */
    String getLabel();

    /** Called when the citizen is assigned this job. */
    boolean start(CitizenEntity citizen, ServerLevel level);

    /** Called when the job is cancelled or stopped. */
    void stop(CitizenEntity citizen, ServerLevel level, boolean announce);

    /** Executed on each server tick for active citizens. */
    void tick(CitizenEntity citizen, ServerLevel level);

    /** Short human-readable status for right-click interaction. */
    String workStatus(CitizenEntity citizen, ServerLevel level);

    /** Detailed snapshot for debug inspection. */
    String debugStatus(CitizenEntity citizen, ServerLevel level);

    /** Indicates whether the job expects the citizen to be moving to a work target. */
    boolean needsNavigation(CitizenEntity citizen, ServerLevel level);

    /** Saves job-specific state to world NBT. */
    void save(ValueOutput output);

    /** Restores job-specific state from world NBT. */
    void load(ValueInput input);
}
