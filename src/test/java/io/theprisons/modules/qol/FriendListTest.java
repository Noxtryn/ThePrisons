package io.theprisons.modules.qol;

import io.theprisons.modules.qol.players.FriendList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Names and chat lines as Cosmic shows them (game logs 2026-10-04 / 05). */
class FriendListTest {
    @Test
    void friendsByNameIgnoringCase() {
        FriendList list = new FriendList();
        assertTrue(list.add("ToyotaSupra_MK4"));
        assertFalse(list.add("toyotasupra_mk4"), "already a friend");
        assertFalse(list.add("not a name"));
        assertEquals(FriendList.Relation.FRIEND, list.relation("TOYOTASUPRA_MK4", null));
        assertTrue(list.remove("toyotaSupra_mk4"));
        assertEquals(FriendList.Relation.NONE, list.relation("ToyotaSupra_MK4", null));
    }

    @Test
    void gangMatesByTheGangInFrontOfTheName() {
        FriendList list = new FriendList();
        assertEquals(FriendList.Relation.NONE, list.relation("ToyotaSupra_MK4", "***Deutsch (42) <Arcanist> ToyotaSupra_MK4 [Escapee]"),
                "own gang not known yet");
        assertTrue(list.chat("(!) You are now a gang member of Deutsch."));
        assertEquals("Deutsch", list.gang());
        assertEquals(FriendList.Relation.GANG, list.relation("ToyotaSupra_MK4", "***Deutsch (42) <Arcanist> ToyotaSupra_MK4 [Escapee]"));
        assertEquals(FriendList.Relation.GANG, list.relation("AimzLikeGod", "Deutsch (17) <Invoker> AimzLikeGod"));
        assertEquals(FriendList.Relation.NONE, list.relation("Luuaap", "***Punjab (90) <Trainee> Luuaap [Riches]"));
        assertEquals(FriendList.Relation.NONE, list.relation("Someone", "(35) <Trainee> Someone"), "no gang");
        list.add("AimzLikeGod");
        assertEquals(FriendList.Relation.FRIEND, list.relation("AimzLikeGod", "Deutsch (17) <Invoker> AimzLikeGod"), "friend wins");
    }

    @Test
    void anotherPlayersStatsDoNotChangeTheKnownGang() {
        FriendList list = new FriendList();
        assertTrue(list.chat("Gang: Deutsch"));
        assertFalse(list.chat("Gang: GangBang"), "someone else's /stats");
        assertEquals("Deutsch", list.gang());
        assertTrue(list.chat("(!) You are now a gang member of Eclipse."));
        assertEquals("Eclipse", list.gang());
    }
}
