package io.theprisons.modules.qol.bandit.dodge;

/** The life of one committed jump: the key is held on the ground, the player lifts off, the course stays locked until the landing. */
public enum JumpPhase {
    NONE, HOLD, AIR
}
