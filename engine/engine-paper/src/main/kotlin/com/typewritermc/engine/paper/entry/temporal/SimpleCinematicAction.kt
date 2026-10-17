package com.typewritermc.engine.paper.entry.temporal

import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.Segment
import com.typewritermc.engine.paper.entry.entries.activeSegmentAt
import com.typewritermc.engine.paper.entry.entries.canFinishAt

abstract class SimpleCinematicAction<S : Segment> : CinematicAction {
    protected var lastFrame = 0
        private set
    private var previousSegment: S? = null
    private var tickedFrame = -1

    override suspend fun tick(frame: Int) {
        val previous = tickedFrame
        tickedFrame = frame
        lastFrame = frame
        super.tick(frame)
        // The frame counter is wall-clock based, so a lagging ticker (or a deliberate frame jump, e.g. a
        // subtitle advance) can step over frames entirely. Segments that lived only inside that gap would
        // otherwise never start: a one-frame console command (warp, fade, music stop) would silently be lost.
        // Replay them in order before handling the current frame.
        if (frame > previous + 1) {
            val skipped = segments
                .filter { it.startFrame > previous && it.endFrame < frame && it != previousSegment }
                .sortedBy { it.startFrame }
            for (missed in skipped) {
                previousSegment?.let { stopSegment(it) }
                startSegment(missed)
                stopSegment(missed)
            }
        }
        val segment = segments activeSegmentAt frame

        if (segment == previousSegment) {
            segment?.let { tickSegment(it, frame) }
            return
        }

        previousSegment?.let { stopSegment(it) }

        segment?.let {
            startSegment(it)
            tickSegment(it, frame)
        }
    }

    override suspend fun teardown() {
        super.teardown()
        previousSegment?.let { stopSegment(it) }
        previousSegment = null
    }

    override fun canFinish(frame: Int): Boolean = segments canFinishAt frame

    protected open suspend fun startSegment(segment: S) {
        previousSegment = segment
    }

    protected open suspend fun stopSegment(segment: S) {
        previousSegment = null
    }

    protected open suspend fun tickSegment(segment: S, frame: Int) {
    }

    abstract val segments: List<S>
}