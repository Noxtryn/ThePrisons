package com.freelocs.theprisons.core.nav;

/** How the player gets from one navigation node to the next. */
public enum MoveType {
    /** Start node of a path. */
    START,
    /** Flat walk or a step the vanilla step height (0.6) handles without jumping. */
    WALK,
    /** Diagonal walk; both side columns are clear for the player box. */
    DIAGONAL,
    /** Height gain above the step height: needs the jump key. */
    JUMP,
    /** Walk off an edge and fall onto a lower floor. */
    DROP
}
