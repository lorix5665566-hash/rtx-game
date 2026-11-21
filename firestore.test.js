const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";
const CHARLIE_UID = "charlie_uid_003";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowMinus(ms = 1000) {
  return new Date(Date.now() - ms);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("players").doc(ALICE_UID).get());
  await assertFails(unauth.collection("playerIds").doc("482915").get());
  await assertFails(unauth.collection("lobbies").doc("lobby_1").get());
  await assertFails(unauth.collection("friendRequests").get());
  await assertFails(unauth.collection("invites").get());
});

test("Player profile & unique 6-digit numeric ID creation, immutability, and ghost-field rejection", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = nowMinus(5000);

  // Valid playerId reservation
  await assertSucceeds(
    alice.collection("playerIds").doc("482915").set({
      shortId: "482915",
      userId: ALICE_UID,
      displayName: "Ghost",
      createdAt: ts,
    })
  );

  // Bob can look up 482915 by exact get(), cannot list all playerIds, cannot hijack it
  await assertSucceeds(bob.collection("playerIds").doc("482915").get());
  await assertFails(bob.collection("playerIds").get());
  await assertFails(bob.collection("playerIds").doc("482915").update({ displayName: "Hacked" }));

  // Valid player profile creation
  await assertSucceeds(
    alice.collection("players").doc(ALICE_UID).set({
      userId: ALICE_UID,
      shortId: "482915",
      displayName: "Ghost",
      online: true,
      activeLobbyId: "",
      totalKills: 0,
      totalHeadshots: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject ghost field & future timestamp & immutable shortId change
  await assertFails(
    bob.collection("players").doc(BOB_UID).set({
      userId: BOB_UID,
      shortId: "551234",
      displayName: "Viper",
      online: true,
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    alice.collection("players").doc(ALICE_UID).update({
      shortId: "999999",
      updatedAt: nowMinus(500),
    })
  );
  await assertSucceeds(
    alice.collection("players").doc(ALICE_UID).update({
      online: false,
      totalKills: 12,
      updatedAt: nowMinus(500),
    })
  );
  await assertFails(bob.collection("players").doc(ALICE_UID).update({ online: false, updatedAt: nowMinus(500) }));
});

test("Friend request flow & mutual friend creation with cross-user protection", async () => {
  const ts = nowMinus(5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("players").doc(ALICE_UID).set({
      userId: ALICE_UID, shortId: "482915", displayName: "Alice", online: true, createdAt: ts, updatedAt: ts,
    });
    await db.collection("players").doc(BOB_UID).set({
      userId: BOB_UID, shortId: "639204", displayName: "Bob", online: true, createdAt: ts, updatedAt: ts,
    });
  });

  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const reqId = `${ALICE_UID}_${BOB_UID}`;

  // Alice sends friend request to Bob
  await assertSucceeds(
    alice.collection("friendRequests").doc(reqId).set({
      fromUid: ALICE_UID,
      fromShortId: "482915",
      fromName: "Alice",
      toUid: BOB_UID,
      toShortId: "639204",
      toName: "Bob",
      status: "pending",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Scoped list queries succeed for Bob & Alice; unfiltered fails
  await assertSucceeds(bob.collection("friendRequests").where("toUid", "==", BOB_UID).get());
  await assertSucceeds(alice.collection("friendRequests").where("fromUid", "==", ALICE_UID).get());
  await assertFails(charlie.collection("friendRequests").get());
  await assertFails(charlie.collection("friendRequests").doc(reqId).get());

  // Bob accepts request & creates mutual friend entries
  await assertSucceeds(
    bob.collection("friendRequests").doc(reqId).update({ status: "accepted", updatedAt: nowMinus(500) })
  );
  // Terminal state lock: cannot re-modify once accepted
  await assertFails(
    bob.collection("friendRequests").doc(reqId).update({ status: "declined", updatedAt: nowMinus(200) })
  );

  await assertSucceeds(
    bob.collection("players").doc(BOB_UID).collection("friends").doc(ALICE_UID).set({
      friendUid: ALICE_UID, friendShortId: "482915", friendName: "Alice", createdAt: nowMinus(500),
    })
  );
  await assertSucceeds(
    bob.collection("players").doc(ALICE_UID).collection("friends").doc(BOB_UID).set({
      friendUid: BOB_UID, friendShortId: "639204", friendName: "Bob", createdAt: nowMinus(500),
    })
  );

  // Charlie cannot read Alice's friends or inject himself
  await assertFails(charlie.collection("players").doc(ALICE_UID).collection("friends").get());
  await assertFails(
    charlie.collection("players").doc(ALICE_UID).collection("friends").doc(CHARLIE_UID).set({
      friendUid: CHARLIE_UID, friendShortId: "777777", friendName: "Charlie", createdAt: nowMinus(500),
    })
  );
  await assertSucceeds(alice.collection("players").doc(ALICE_UID).collection("friends").get());
});

test("Multiplayer lobby creation, friend invite, join, team selection, state sync, and terminal lock", async () => {
  const ts = nowMinus(5000);
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const lobbyId = "lobby_test_01";

  // Alice creates lobby (1v1 / friends-only: allowBots = false)
  await assertSucceeds(
    alice.collection("lobbies").doc(lobbyId).set({
      hostUid: ALICE_UID,
      hostName: "Alice",
      mapId: "MIRAGE",
      mode: "BOMB_DEFUSAL",
      roundsToWin: 8,
      allowBots: false,
      status: "waiting",
      memberUids: [ALICE_UID],
      tScore: 0,
      ctScore: 0,
      roundNumber: 1,
      bombState: "CARRIED",
      bombX: 0,
      bombY: 0,
      bombZ: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice invites Bob
  await assertSucceeds(
    alice.collection("invites").doc("inv_01").set({
      lobbyId,
      fromUid: ALICE_UID,
      fromName: "Alice",
      fromShortId: "482915",
      toUid: BOB_UID,
      mapId: "MIRAGE",
      mode: "BOMB_DEFUSAL",
      status: "pending",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(bob.collection("invites").where("toUid", "==", BOB_UID).get());
  await assertSucceeds(bob.collection("invites").doc("inv_01").update({ status: "accepted", updatedAt: nowMinus(500) }));

  // Bob joins lobby by appending himself to memberUids
  await assertSucceeds(
    bob.collection("lobbies").doc(lobbyId).update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: nowMinus(400),
    })
  );
  // Bob cannot hijack host settings (e.g. change mapId)
  await assertFails(
    bob.collection("lobbies").doc(lobbyId).update({
      mapId: "DUST2",
      updatedAt: nowMinus(300),
    })
  );

  // Alice (T) and Bob (CT) write their own player states (1v1!)
  await assertSucceeds(
    alice.collection("lobbies").doc(lobbyId).collection("states").doc(ALICE_UID).set({
      userId: ALICE_UID, shortId: "482915", displayName: "Alice", team: "T", ready: true,
      x: 38.5, y: 0.0, z: -5.5, yawDeg: 0, pitchDeg: 0, hp: 100, dead: false,
      moving: false, shooting: false, weapon: "AK47", kills: 0, deaths: 0, hitSeq: 0, updatedAt: nowMinus(300),
    })
  );
  await assertSucceeds(
    bob.collection("lobbies").doc(lobbyId).collection("states").doc(BOB_UID).set({
      userId: BOB_UID, shortId: "639204", displayName: "Bob", team: "CT", ready: true,
      x: -18.5, y: 0.0, z: 24.5, yawDeg: 180, pitchDeg: 0, hp: 100, dead: false,
      moving: false, shooting: false, weapon: "M4A1_S", kills: 0, deaths: 0, hitSeq: 0, updatedAt: nowMinus(300),
    })
  );

  // Members can read each other's state via get(), non-member Charlie cannot; list is denied
  await assertSucceeds(alice.collection("lobbies").doc(lobbyId).collection("states").doc(BOB_UID).get());
  await assertFails(charlie.collection("lobbies").doc(lobbyId).collection("states").doc(BOB_UID).get());
  await assertFails(alice.collection("lobbies").doc(lobbyId).collection("states").get());
  // Bob cannot overwrite Alice's state
  await assertFails(
    bob.collection("lobbies").doc(lobbyId).collection("states").doc(ALICE_UID).update({ hp: 0, updatedAt: nowMinus(200) })
  );

  // Host starts and finishes match -> terminal lock prevents further edits
  await assertSucceeds(
    alice.collection("lobbies").doc(lobbyId).update({ status: "playing", allowBots: true, updatedAt: nowMinus(200) })
  );
  await assertSucceeds(
    alice.collection("lobbies").doc(lobbyId).update({ status: "finished", tScore: 8, updatedAt: nowMinus(100) })
  );
  await assertFails(
    alice.collection("lobbies").doc(lobbyId).update({ status: "playing", updatedAt: nowMinus(50) })
  );
});
