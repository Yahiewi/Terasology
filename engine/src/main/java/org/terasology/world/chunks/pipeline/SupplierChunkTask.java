// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.world.chunks.pipeline;

import org.joml.Vector3i;
import org.terasology.world.chunks.Chunk;

import java.util.function.Supplier;

public class SupplierChunkTask implements ChunkTask {

    private final String taskName;
    private final Supplier<Chunk> supplier;
    private Chunk resultChunk;

    public SupplierChunkTask(String taskName, Supplier<Chunk> supplier) {
        this.taskName = taskName;
        this.supplier = supplier;
    }

    @Override
    public Chunk getChunk() {
        return resultChunk;
    }

    @Override
    public String getName() {
        return taskName;
    }

    @Override
    public void run() {
        resultChunk = supplier.get();
    }

    @Override
    public boolean isTerminateSignal() {
        return false;
    }

    @Override
    public Vector3i getPosition() {
        if (getChunk() == null) {
            return new Vector3i();
        }
        return ChunkTask.super.getPosition();
    }
}
