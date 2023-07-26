/*
https://releases.battlecode.org/specs/battlecode23/1.2.0/specs.md.html#actionsandcooldowns
https://github.com/battlecode/battlecode23/blob/master/engine/src/main/battlecode/instrumenter/bytecode/resources/MethodCosts.txt
https://releases.battlecode.org/javadoc/battlecode23/1.2.4/index.html
https://play.battlecode.org/bc23/getting-started

*/

package realplayer2;

import battlecode.common.*;
import battlecode.instrumenter.inject.RobotMonitor;
import battlecode.world.Island;

import java.util.Arrays;
import java.util.Random;

final class MyWellInfo {
	MapLocation loc;
	ResourceType resourceType;

	public MyWellInfo(MapLocation loc, ResourceType resourceType) {
		this.loc = loc;
		this.resourceType = resourceType;
	}

	public String toString() {
		return loc.toString();
	}
}

final class MyIslandInfo {
	MapLocation loc;
	Team team;
	int index;

	public MyIslandInfo(int index, Team team, MapLocation loc) {
		this.loc = loc;
		this.team = team;
		this.index = index;
	}

	public String toString() {
		return loc.toString();
	}
}

enum MoveStatus {
	ON, ADJACENT, FAILED
}

enum Symmetry {
	ROTATION, HORIZONTAL, VERTICAL, UNKNOWN
}

public strictfp class RobotPlayer {

	static final int HQ_START = 0, HQ_END = 3, ENEMY_HQ_START = 4, ENEMY_HQ_END = 7, WELL_START = 8, WELL_END = 13,
			ISLAND_START = 14, ISLAND_END = 48, SYMMETRY_INDEX = 49;

	static int turnCount = 0;

	static final Random rng = new Random();

	static void yield(int a, RobotController rc) throws GameActionException {
		if (Clock.getBytecodesLeft() < a) {
			Clock.yield();
			readAllInformation(rc);
		}
	}

	private static void writeHQLocationToSharedArray(RobotController rc, MapLocation loc, boolean isEnemy)
			throws GameActionException {
		if (loc == null)
			return;

		int d = loc.y << 6 | loc.x | (1 << 12);
		int i = !isEnemy ? HQ_START : ENEMY_HQ_START;
		for (; i <= (!isEnemy ? HQ_END : ENEMY_HQ_END); i++) {
			int v = rc.readSharedArray(i);
			if (v == d)
				return;
			if (v == 0)
				break;
		}
		if (i <= (!isEnemy ? HQ_END : ENEMY_HQ_END) && rc.canWriteSharedArray(i, d)) {
			rc.writeSharedArray(i, d);
		}

		// 13 bits (the 1 << 12 is to ensure that (0, 0) is a non 0 number -> 0 is
		// equivalent to null)
		// 0..6 7..12 13 for: x y control
	}

	private static MapLocation readHQLocationFromSharedArray(RobotController rc, int index, boolean isEnemy)
			throws GameActionException {
		int coord = rc.readSharedArray(index + (!isEnemy ? HQ_START : ENEMY_HQ_START));
		if (coord == 0)
			return null;

		return new MapLocation(coord & 0b111111, (coord >> 6) & 0b111111);
	}

	private static void writeWellInfoToSharedArray(RobotController rc, WellInfo wi) throws GameActionException {
		if (wi == null)
			return;

		if (!rc.onTheMap(wi.getMapLocation()))
			return;

		yield(500, rc);

		int d = wi.getMapLocation().y << 6 | wi.getMapLocation().x | (1 << 14);
		d |= ((wi.getResourceType().ordinal() & 0b11) << 12);
		// 0..6 7..12 13 14 15 for: x y type type control // two bits for the type 00 =
		// AD, 01 = EX, 10 = MN, 11 = NO

		int i = WELL_START;
		for (; i <= WELL_END; i++) {
			int v = rc.readSharedArray(i);
			if (v == d)
				return;
			if (v == 0)
				break;
		}
		if (i <= WELL_END && rc.canWriteSharedArray(i, d)) {
			rc.writeSharedArray(i, d);
		}
	}

	private static MyWellInfo readWellInfoFromSharedArray(RobotController rc, int index) throws GameActionException {
		int well = rc.readSharedArray(index + WELL_START);

		if (well == 0)
			return null;

		return new MyWellInfo(new MapLocation(well & 0b111111, (well >> 6) & 0b111111),
				ResourceType.values()[(well >> 12) & 0b11]);
	}

	private static void writeIslandInfoToSharedArray(RobotController rc, int islandIndex, MapLocation loc, Team team)
			throws GameActionException {
		int island = rc.readSharedArray(islandIndex + ISLAND_START);
		int d = loc.y << 6 | loc.x | (1 << 14);
		if (team == Team.B)
			d |= (1 << 12);
		if (team == Team.NEUTRAL)
			d |= (2 << 12);
		// 0..6 7..12 13 14 15 for: x y team team control // index of island +
		// ISLAND_START =
		// index in shared array
		// two bits for team: 00 = A, 01 = B, 10 = Neutral

		if (d >> 12 == island >> 12)
			return; // ignore location data
		if (rc.canWriteSharedArray(islandIndex + ISLAND_START, d)) {
			rc.writeSharedArray(islandIndex + ISLAND_START, d);
		}
	}

	private static MyIslandInfo readIslandInfoFromSharedArray(RobotController rc, int islandIndex)
			throws GameActionException {
		int island = rc.readSharedArray(islandIndex + ISLAND_START);
		if (island == 0)
			return null;
		return new MyIslandInfo(islandIndex, Team.values()[(island >> 12) & 0b11],
				new MapLocation(island & 0b111111, (island >> 6) & 0b111111));
	}

	private static void writeMapSymmetryToSharedArray(RobotController rc, Symmetry symmetry) throws GameActionException {
		int sym = rc.readSharedArray(SYMMETRY_INDEX);
		if (sym == symmetry.ordinal())
			return;
		// 00 = Rotation, 01 = Hor, 10 = Vert, 11 = Unknown
		if (rc.canWriteSharedArray(SYMMETRY_INDEX, symmetry.ordinal())) {
			rc.writeSharedArray(SYMMETRY_INDEX, symmetry.ordinal());
		}
	}

	private static Symmetry readMapSymmetryFromSharedArray(RobotController rc) throws GameActionException {
		int sym = rc.readSharedArray(SYMMETRY_INDEX);
		return Symmetry.values()[sym];
	}

	static MapLocation[] hqs = new MapLocation[HQ_END - HQ_START + 1];
	static MapLocation[] enemyHqs = new MapLocation[ENEMY_HQ_END - ENEMY_HQ_START + 1];
	static MyWellInfo[] wells = new MyWellInfo[WELL_END - WELL_START + 1];
	static MyIslandInfo[] islands = new MyIslandInfo[ISLAND_END - ISLAND_START + 1];
	static RobotInfo[] nearbyRobots = new RobotInfo[0];
	static Symmetry mapSymmetry = Symmetry.UNKNOWN;

	private static MapLocation getSymmetricLocation(RobotController rc, MapLocation loc) throws GameActionException {
		int oppX = rc.getMapWidth() - 1 - loc.x;
		int oppY = rc.getMapHeight() - 1 - loc.y;

		Symmetry sym = mapSymmetry;
		if (sym == Symmetry.UNKNOWN)
			sym = Symmetry.values()[rng.nextInt(3)]; // random but known symmetry

		if (sym == Symmetry.VERTICAL) {
			return new MapLocation(oppX, loc.y);
		} else if (sym == Symmetry.HORIZONTAL) {
			return new MapLocation(loc.x, oppY);
		} else { // rotation
			return new MapLocation(oppX, oppY);
		}
	}

	static boolean canMoveBetter(RobotController rc, Direction mv) throws GameActionException {
		return rc.canMove(mv) && (
					 rc.senseMapInfo(rc.getLocation().add(mv)).getCurrentDirection() != mv.opposite() ||
					 rc.getType() == RobotType.CARRIER);
	}

	static MoveStatus moveToLocation(RobotController rc, MapLocation target, int range) throws GameActionException {
		Direction to, move;
		to = move = rc.getLocation().directionTo(target);
		boolean rushStatusBefore = !(rc.getRobotCount() < RUSH_ROBOT_THRESHOLD && rc.getRoundNum() < RUSH_ROUND_THRESHOLD);
		for (int i = 0; i < 60;) { // 200 rotations to try to move out of wall -> hugs the wall to escape
			boolean rushStatusAfter = !(rc.getRobotCount() < RUSH_ROBOT_THRESHOLD && rc.getRoundNum() < RUSH_ROUND_THRESHOLD);
			// if launcher -> break out of move cycle if time to rush (switched from not
			// rushing to rushing)
			if (rc.getType() == RobotType.LAUNCHER && !rushStatusBefore && rushStatusAfter) {
				break;
			}
			if (rc.getType() == RobotType.LAUNCHER && rc.isActionReady()) {
				attackRobot(rc, RobotType.LAUNCHER);
				attackRobot(rc, null);
			}

			yield(50, rc);
			if (range > 0) {
				if (rc.getLocation().distanceSquaredTo(target) <= (range+1)*(range+1)) {
					return MoveStatus.ON;
				} 
			}
			else {
				if (rc.getLocation().equals(target)) {
					return MoveStatus.ON;
				} else if (rc.getLocation().isAdjacentTo(target) && rc.isLocationOccupied(target)) {
					return MoveStatus.ADJACENT;
				}
			}

			yield(200, rc);
			if (canMoveBetter(rc, move) && (rc.getRoundNum() % 4 != 0 || rc.getType() != RobotType.LAUNCHER)) {
				rc.move(move);
				i++; // iterate only if it successfully moves
				readAllInformation(rc);
				updateSharedArrays(rc);
				if (move != to) {
					move = move.rotateRight();
				} else {
					to = move = rc.getLocation().directionTo(target);
					// if it might have a clear path (headed in the correct direction) -> then
					// reupdate directions
				}
			} else if (rc.isMovementReady()) { // movement was ready but could not move -> still blocked
				move = move.rotateLeft();
			} else {
				Clock.yield(); // yield (cannot move)
				// readAllInformation(rc);
			}
		}
		return MoveStatus.FAILED;
	}

	static void updateSharedArrays(RobotController rc) throws GameActionException {
		yield(500, rc);
		for (WellInfo wi : rc.senseNearbyWells()) {
			writeWellInfoToSharedArray(rc, wi);
		}

		yield(500, rc);
		for (int islandIndex : rc.senseNearbyIslands()) {
			MapLocation loc = rc.senseNearbyIslandLocations(islandIndex)[0];
			writeIslandInfoToSharedArray(rc, islandIndex, loc, rc.senseTeamOccupyingIsland(islandIndex));
		}

		yield(500, rc);
		for (RobotInfo robot : nearbyRobots) {
			if (robot.team == rc.getTeam().opponent() && robot.type == RobotType.HEADQUARTERS) {
				// ENEMY Headquarters:
				writeHQLocationToSharedArray(rc, robot.getLocation(), true);
			}
		}
	}

	static void readAllInformation(RobotController rc) throws GameActionException {
		if (turnCount == 1) {
			// read locations of all our HQs
			for (int i = 0; i <= HQ_END - HQ_START; i++) {
				if (hqs[i] != null) {
					continue;
				}
				hqs[i] = readHQLocationFromSharedArray(rc, i, false);
			}
		}

		yield(100, rc);
		// read all Enemy Hqs
		for (int i = 0; i <= ENEMY_HQ_END - ENEMY_HQ_START; i++) {
			if (enemyHqs[i] != null) {
				continue;
			}
			enemyHqs[i] = readHQLocationFromSharedArray(rc, i, true);
		}

		yield(100, rc);
		// read all wells
		for (int i = 0; i <= WELL_END - WELL_START; i++) {
			if (wells[i] != null)
				continue;
			wells[i] = readWellInfoFromSharedArray(rc, i);
			if (wells[i] == null)
				break;
		}

		yield(500, rc);
		// read all islands
		for (int i = 0; i <= ISLAND_END - ISLAND_START; i++) {
			islands[i] = readIslandInfoFromSharedArray(rc, i);
		}

		// read symmetry info
		yield(100, rc);
		mapSymmetry = readMapSymmetryFromSharedArray(rc);

		// sense nearby robots
		nearbyRobots = rc.senseNearbyRobots();
	}

	private static MapLocation randomHq(boolean enemy) {
		MapLocation[] iter = !enemy ? hqs : enemyHqs;
		int i = 0; // random index
		for (; i < iter.length; i++) {
			if (iter[i] == null)
				break;
		}
		if (i == 0)
			return null;
		return iter[rng.nextInt(i)];
	}

	static private MyWellInfo[] sortArrayByDistanceToLocation(MyWellInfo[] locs, MapLocation compare) {
		MyWellInfo old;
		MyWellInfo neww;
		for (int i = 0; i<locs.length - 1; i++) {
			for (int j = 0; j<locs.length - i - 1; j++) {
				old = locs[j];
				neww = locs[j+1];
				if (old.loc.distanceSquaredTo(compare) > neww.loc.distanceSquaredTo(compare)) {
					locs[j] = neww;
					locs[j+1] = old;
				}
			}
		}
		return locs;
	}

	private static MyWellInfo randomWeightedWell(ResourceType target, MapLocation loc) {
		int wellCount = 0;
		for (MyWellInfo wi : wells) {
			if (wi != null && wi.resourceType == target) {
				wellCount++;
			}
		}
		if (wellCount == 0)
			return null;
		
		MyWellInfo[] locations = new MyWellInfo[wellCount];
		int i = 0;
		for (MyWellInfo wi : wells) {
			if (wi != null && wi.resourceType == target) {
				locations[i] = wi;
				i++;
			}
		}

		locations = sortArrayByDistanceToLocation(locations, loc);

		for (int j = 0; j<locations.length; j++) {
			int r = rng.nextInt(3);
			if (r < 2 || j == locations.length - 1) {
				return locations[j];
			}
		}
		return null;
	}

	private static MyWellInfo randomWell(ResourceType target) {
		int wellCount = 0;
		for (MyWellInfo wi : wells) {
			if (wi != null && wi.resourceType == target) {
				wellCount++;
			}
		}
		if (wellCount == 0)
			return null;

		int index = rng.nextInt(wellCount);
		wellCount = -1;
		for (MyWellInfo wi : wells) {
			if (wi != null && wi.resourceType == target && ++wellCount == index) {
				return wi;
			}
		}
		return null;
	}

	private static MyIslandInfo randomIsland(Team team) {
		// count total islands
		int islandCount = 0;
		for (MyIslandInfo i : islands) {
			if (i != null && i.team == team) {
				islandCount++;
			}
		}
		if (islandCount == 0) {
			return null;
		}

		// select random island
		int index = rng.nextInt(islandCount);
		islandCount = -1;
		for (MyIslandInfo i : islands) {
			if (i != null && i.team == team && ++islandCount == index) {
				return i;
			}
		}
		return null;
	}

	private static void hqFigureOutSymmetry(RobotController rc) throws GameActionException {
		yield(2000, rc); // heavy processing (2000 should be more than enough)
		int rC = 0; // rotation matches
		int vC = 0; // vertical matches
		int hC = 0; // horizontal matches

		int width = rc.getMapWidth() - 1; // - 1 for the last index
		int height = rc.getMapHeight() - 1;

		// check hqs for matches
		for (MapLocation hq : hqs) {
			for (MapLocation eHq : enemyHqs) {
				if (eHq == null || hq == null) {
					continue;
				}
				if (hq.x + eHq.x == width && hq.y + eHq.y == height)
					rC++;
				if (hq.x == eHq.x && hq.y + eHq.y == height)
					hC++;
				if (hq.x + eHq.x == width && hq.y == eHq.y)
					vC++;
			}
		}

		// check wells for matches
		for (MyWellInfo wi : wells) {
			for (MyWellInfo wi2 : wells) {
				if (wi == null || wi2 == null) {
					continue;
				}
				if (wi.loc.x + wi2.loc.x == width && wi.loc.y + wi2.loc.y == height)
					rC++;
				if (wi.loc.x == wi2.loc.x && wi.loc.y + wi2.loc.y == height)
					hC++;
				if (wi.loc.x + wi2.loc.x == width && wi.loc.y == wi2.loc.y)
					vC++;
			}
		}

		yield(200, rc);
		// the most matches is our symmetry
		if (rC > vC && rC > hC) {
			writeMapSymmetryToSharedArray(rc, Symmetry.ROTATION);
			// rc.setIndicatorString("ROT");
		} else if (vC > hC && vC > rC) {
			writeMapSymmetryToSharedArray(rc, Symmetry.VERTICAL);
			// rc.setIndicatorString("VERT");
		} else if (hC > vC && hC > rC) {
			writeMapSymmetryToSharedArray(rc, Symmetry.HORIZONTAL);
			// rc.setIndicatorString("HORIZ");
		} else {
			// should never happen
			writeMapSymmetryToSharedArray(rc, Symmetry.UNKNOWN);
		}
	}

	// Robot code
	// ===============================================================================================

	@SuppressWarnings("unused")
	public static void run(RobotController rc) throws GameActionException {
		while (true) {
			turnCount += 1;
			try {
				switch (rc.getType()) {
					case HEADQUARTERS:
						runHeadquarters(rc);
						break;
					case CARRIER:
						runCarrier(rc);
						break;
					case LAUNCHER:
						runLauncher(rc);
						break;
					case BOOSTER:
						runBooster(rc);
						break;
					case DESTABILIZER:
						runDestabilizer(rc);
						break;
					case AMPLIFIER:
						runAmplifier(rc);
						break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("ERROR!");
			} finally {
				Clock.yield();
				readAllInformation(rc);
			}
		}
	}

	// Based on direction and square distance, it gets the discrete
	// Number of squares it should go to
	static MapLocation addMultipleToLocation(RobotController rc, Direction d, int squareRep) {
		MapLocation loc = rc.getLocation();
		double div = 1;

		// Just precalculated sqrt(2)
		if (Math.abs(d.dx) + Math.abs(d.dy) > 1) div = 1.41421356237;

		int dist = (int)(Math.sqrt(squareRep / div));
		for (int i = 0; i<dist; i++)
			loc = loc.add(d);
		return loc;
	}

	static Direction launcherDirection;
	static void runHeadquarters(RobotController rc) throws GameActionException {
		if (turnCount == 1) { // First turn
			writeHQLocationToSharedArray(rc, rc.getLocation(), false); // update array to hold HQ location
			updateSharedArrays(rc); // update nearby items -> well and islands
			
			// Figures out closest direction to the center
			// This is the only direction launchers use so
			// We should add more or make it default to the
			// Regular direction.
			launcherDirection = rc.getLocation().directionTo(new MapLocation(rc.getMapHeight() / 2, rc.getMapWidth() / 2));
		}
		readAllInformation(rc);

		// do processing (figure out symmetry)
		hqFigureOutSymmetry(rc);

		// create anchors
		if (turnCount > 750 && rc.isActionReady()) {
			// give carriers time to spawn and collect resources
			int anchorCount = rc.getNumAnchors(Anchor.STANDARD); // number of anchors this HQ is carrying
			int freeIslandCount = 0;
			for (MyIslandInfo islandInfo : islands) {
				if (islandInfo != null && islandInfo.team == Team.NEUTRAL) {
					freeIslandCount++;
				}
			}
			int hqCount = 0;
			for (MapLocation l : hqs) {
				if (l != null)
					hqCount++;
			}

			if (freeIslandCount - anchorCount * hqCount > 0) {
				// assume each HQ produces the same amount of anchors
				if (rc.canBuildAnchor(Anchor.STANDARD)) {
					rc.buildAnchor(Anchor.STANDARD);
				} else {
					return; // yield turn to collect resources to build an anchor
				}
			}
		}

		// Spawn carriers to scout for resources -> check all directions for
		// availability
		if (rc.isActionReady()) {
			// First 7 rounds: Launcher, Launcher, Carrier, Signal Amplifier, Carrier,
			// Carrier, Carrier
			loop: for (Direction d : Direction.allDirections()) {
				yield(400, rc);

				// TEMPORARY
				// TODO: Make it try to spawn towards the center.
				launcherDirection = d;

				switch (rc.getRoundNum()) {
					case 1:
						if (rc.canBuildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1))) {
							rc.buildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1));
						}
						if (rc.canBuildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 4))) {
							rc.buildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 4));
						}
						if (rc.canBuildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 9))) {
							rc.buildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 9));
						}
						break;
					case 2:
					case 3:
						// on small maps produce LAUNCHER instead of signal amplifier
						// if (rc.getMapWidth() > 30 && rc.canBuildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9))) {
						// 	rc.buildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9));
						// 	break loop;
						// } 
						// else if (rc.canBuildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1))) {
						// 	rc.buildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1));
						// 	break loop;
						// }
						// System.out.println(addMultipleToLocation(rc, launcherDirection, 1));
						// System.out.println(rc.canBuildRobot(RobotType.CARRIER, addMultipleToLocation(rc, launcherDirection, 1)));
						break;
					case 4:
					case 5:
					case 6:
					case 30:
						if (rc.canBuildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9))) {
							rc.buildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9));
							break loop;
						}
						break;
					default:
						int choose = rng.nextInt(60);
						if (choose < 1 && rc.canBuildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9)) && rc.getRoundNum() > 250) {
							rc.buildRobot(RobotType.AMPLIFIER, addMultipleToLocation(rc, d, 9));
							break loop;
						} 
						else if (choose < 54 && rc.canBuildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1))) {
							rc.buildRobot(RobotType.LAUNCHER, addMultipleToLocation(rc, launcherDirection, 1));
							break loop;
						} 
						else if (choose < 60 && rc.canBuildRobot(RobotType.CARRIER, addMultipleToLocation(rc, d, 9))) {
							rc.buildRobot(RobotType.CARRIER, addMultipleToLocation(rc, d, 9));
							break loop;
						}
				}
			}
		}
	}

	static MapLocation startingHQ;

	static void runCarrier(RobotController rc) throws GameActionException {
		readAllInformation(rc);

		/*
		 * 1. Island
		 * - sense nearby anchors -> write all necessary information
		 * - if carrying anchor -> go to nearby unoccupied island
		 * (senseTeamOccupyingIsland)
		 * 2. Resources
		 * - sense nearby wells -> write all info
		 * - if available space to collect -> go to nearby well
		 * - if adjacent to well -> collect
		 * - if full -> go back to HQ
		 */

		updateSharedArrays(rc);

		if (turnCount == 1) {
			for (RobotInfo ri: rc.senseNearbyRobots()) {
				if (ri.type == RobotType.HEADQUARTERS) startingHQ = ri.location;
			}
		}

		// if nearby launcher and can attack -> attack
		for (RobotInfo ri : nearbyRobots) {
			if (ri.team == rc.getTeam().opponent()) {
				yield(50, rc);
				if (ri.type == RobotType.LAUNCHER && rc.canAttack(ri.location)) {
					rc.attack(ri.location);
				}
			}
		}

		// Handle Islands ================================

		MyIslandInfo targetIsland = null;
		for (MyIslandInfo island : islands) {
			if (island != null && island.team == Team.NEUTRAL) {
				if (targetIsland == null
						|| island.loc.distanceSquaredTo(rc.getLocation()) < targetIsland.loc.distanceSquaredTo(rc.getLocation())) {
					targetIsland = island;
				}
			}
		}

		if (targetIsland != null && rc.isActionReady()) {
			// if nearby available island, then try to pick anchor from HQ
			for (MapLocation hq : hqs) {
				yield(200, rc);
				if (hq != null && rc.getLocation().isAdjacentTo(hq) && rc.canTakeAnchor(hq, Anchor.STANDARD)) {
					rc.takeAnchor(hq, Anchor.STANDARD);
					break;
				}
			}
			if (rc.getNumAnchors(Anchor.STANDARD) > 0) {
				// if successfully picked up the anchor -> go to island
				if (moveToLocation(rc, targetIsland.loc, 0) == MoveStatus.ON) {
					yield(200, rc);
					if (rc.isActionReady()) {
						rc.setIndicatorString(targetIsland.index + ", " + islands[targetIsland.index].team);
						if (islands[targetIsland.index].team == rc.getTeam()) {
							// we already own it -> yield and decide what to do next turn
							return;
						} else if (rc.canPlaceAnchor()) {
							rc.placeAnchor();
							updateSharedArrays(rc);
						}
					}
				}
				// if not on island -> return and hopefully get on next turn
				return;
			}
		}

		// Handle Wells ================================

		// If full, go to random HQ and deposit
		if (rc.getResourceAmount(ResourceType.MANA) + rc.getResourceAmount(ResourceType.ADAMANTIUM) == 40) {
			// select a random valid hq
			MapLocation hqLoc = randomHq(false);

			if (moveToLocation(rc, hqLoc, 0) == MoveStatus.FAILED)
				return;
			// Deposit all MANA
			while (rc.getResourceAmount(ResourceType.MANA) > 0) {
				if (!rc.isActionReady()) {
					Clock.yield(); // end turn -> continue depositing on next turn
					readAllInformation(rc);
					continue;
				}

				if (rc.canTransferResource(hqLoc, ResourceType.MANA, rc.getResourceAmount(ResourceType.MANA))) {
					rc.transferResource(hqLoc, ResourceType.MANA, rc.getResourceAmount(ResourceType.MANA));
				}
			}
			// Deposit all ADAMANTIUM
			while (rc.getResourceAmount(ResourceType.ADAMANTIUM) > 0) {
				if (!rc.isActionReady()) {
					Clock.yield(); // end turn -> continue depositing on next turn
					readAllInformation(rc);
					continue;
				}

				if (rc.canTransferResource(hqLoc, ResourceType.ADAMANTIUM,
						rc.getResourceAmount(ResourceType.ADAMANTIUM))) {
					rc.transferResource(hqLoc, ResourceType.ADAMANTIUM, rc.getResourceAmount(ResourceType.ADAMANTIUM));
				}
			}
			return;
		}

		// otherwise, if not full, go to a random well
		MyWellInfo manaWell = randomWeightedWell(ResourceType.MANA, startingHQ);
		MyWellInfo adWell = randomWeightedWell(ResourceType.ADAMANTIUM, startingHQ);

		MyWellInfo targetWell = adWell;
		if (manaWell != null && rng.nextInt(10) < 7) {
			targetWell = manaWell; // 70% chance of attacking a mana well
		}

		if (moveToLocation(rc, targetWell.loc, 0) == MoveStatus.FAILED) {
			return;
		}
		while (rc.getResourceAmount(ResourceType.MANA) + rc.getResourceAmount(ResourceType.ADAMANTIUM) < 40) {
			if (rc.isActionReady()) {
				if (rc.canCollectResource(targetWell.loc, -1)) {
					rc.collectResource(targetWell.loc, -1);
				}
			} else {
				Clock.yield();
				readAllInformation(rc);
			}
		}
	}

	private static boolean launcherIsStationed = false;

	/**
	 * 
	 * @param locOne the first location
	 * @param locTwo the second location\n
	 * 
	 * @return Gets the direction from locOne to locTwo
	 */
	static double directionTo(MapLocation locOne, MapLocation locTwo) {
		return Math.atan2(locTwo.x - locOne.x, locTwo.y - locOne.y);
	}

	static void launcherAttackMode(RobotController rc) throws GameActionException {
		/*
		 * Figure out what general direction the enemy is at locally
		 * If we have lower health than a launcher behind us, move in the
		 * opposite direction of the enemy.
		 * If we have more health than a launcher in front of us
		 * move in the direction of the enemy.
		 * 
		 * I'll work on this later, but I think it works like it's supposed to. It finds
		 * The lowest health or highest health ally launcher depending on the direction
		 * of the nearest enemy.
		 */
		
		// Figuring general direction of enemy based on local info
		int count = 0, launcherX = 0, launcherY = 0, arrayPoint = 0;
		boolean teamLauncherNearby = false;
		RobotInfo[] teamLaunchers = new RobotInfo[80];  // This is like 80 bytecode I think
		for (RobotInfo r: nearbyRobots) {
			if (r.type == RobotType.LAUNCHER && r.team != rc.getTeam()) {
				launcherX += r.location.x;
				launcherY += r.location.y;
				count++;
			}
			else if (r.type == RobotType.LAUNCHER && r.team == rc.getTeam()) {
				teamLaunchers[arrayPoint] = r;

				arrayPoint++;
				teamLauncherNearby = true;
			}
		}
		if (!teamLauncherNearby) {
			return;
		}
		if (count == 0) {
			return;
		}


		MapLocation avgLocation = new MapLocation(launcherX / count, launcherY / count);
		double enemyDirection = directionTo(rc.getLocation(), avgLocation);
		double oppEnemyDirection = (Math.PI + enemyDirection) % (Math.PI * 2);
		
		int bestHealth = -1, worstHealth = 9999;
		RobotInfo best = null, worst = null;
		for (int i = 0; i<arrayPoint; i++) {
			RobotInfo launch = teamLaunchers[i];
			if (Math.abs(oppEnemyDirection - directionTo(rc.getLocation(), launch.location)) < Math.PI/4) {
				System.out.println("YAY");
				if (launch.health > bestHealth) {
					bestHealth = launch.health;
					best = launch;
				}
			}
			else if (Math.abs(enemyDirection - directionTo(rc.getLocation(), launch.location)) < Math.PI/4) {
				if (launch.health < worstHealth) {
					worstHealth = launch.health;
					worst = launch;
				}
			}
		}
		if (worst != null) {
			System.out.println(worst.toString());
			System.out.println(rc.getRoundNum());
			rc.setIndicatorString("Worst Robot behind: " + worst);
		}
	}

	static void attackRobot(RobotController rc, RobotType type) throws GameActionException {
		RobotInfo attack = null;
		int lowestHealth = 999999;
		for (RobotInfo r : nearbyRobots) {
			if (!rc.isActionReady()) {
				break;
			}
			if (r.team == rc.getTeam().opponent() && r.getType() != RobotType.HEADQUARTERS
					&& rc.canAttack(r.getLocation())) {
				if (r.getType() == type || type == null) {
					if (r.getHealth() < lowestHealth) {
						lowestHealth = r.getHealth();
						attack = r;
					}
				}
			}
		}
		if (attack != null) {
			rc.attack(attack.getLocation());

			if (attack.type != RobotType.LAUNCHER) return;
			Direction dir = rc.getLocation().directionTo(attack.location).opposite();
			if (rc.canMove(dir)) {
				rc.move(dir);
			}
			else if (rc.canMove(dir.rotateLeft())) {
				rc.move(dir.rotateLeft());
			}
			else if (rc.canMove(dir.rotateRight())) {
				rc.move(dir.rotateRight());
			}
		}
	}

	private static int RUSH_ROBOT_THRESHOLD = 50;
	private static int RUSH_ROUND_THRESHOLD = 750;

	static void runLauncher(RobotController rc) throws GameActionException {
		readAllInformation(rc);
		updateSharedArrays(rc);
		//launcherAttackMode(rc);

		if (turnCount == 1) {
			int width = rc.getMapWidth();
			int height = rc.getMapHeight();
			RUSH_ROBOT_THRESHOLD = (width + height) * 5 / 4; // arbitrary
			RUSH_ROUND_THRESHOLD = RUSH_ROBOT_THRESHOLD * 5; // arbitrary
		}

		// Rounds to wait before rushing:
		if (rc.getRobotCount() < RUSH_ROBOT_THRESHOLD && rc.getRoundNum() < RUSH_ROUND_THRESHOLD) {
			// rush only after 50 total robots -> ~30ish launchers
			moveToLocation(rc, new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2), 0);
			// move to the center
			return;
		}

		if (launcherIsStationed) {
			// sense for enemy robots or enemy anchors here
			rc.setIndicatorString("STATIONED");
			for (MyIslandInfo island : islands) {
				if (!rc.isActionReady()) {
					break;
				}
				if (island != null && island.team == rc.getTeam().opponent() && rc.canAttack(island.loc)) {
					rc.attack(island.loc);
				}
			}

			attackRobot(rc, RobotType.LAUNCHER);
			attackRobot(rc, null);
			return;
		}

		int goal = rng.nextInt(100);
		// 30% -> defend our islands
		// 30% -> attack enemy islands
		// 40% -> swarm enemy hq

		MyIslandInfo island = randomIsland(rc.getTeam());
		MyIslandInfo eIsland = randomIsland(rc.getTeam().opponent());
		MapLocation eHq = randomHq(true);
		MapLocation eHqGuess = getSymmetricLocation(rc, randomHq(false));

		if (goal < 30 && island != null) {
			rc.setIndicatorString("ISLAND");
			if (moveToLocation(rc, island.loc, 0) != MoveStatus.FAILED) {
				// scan nearby robots (if many -> then go elsewhere)
				int friendlyLaunchers = 0;
				for (RobotInfo ri : nearbyRobots) {
					if (ri.team == rc.getTeam() && ri.type == RobotType.LAUNCHER) {
						friendlyLaunchers++;
					}
				}
				if (friendlyLaunchers < 3) {
					// if less than 3 other robots are present -> stay
					launcherIsStationed = true;
				}
			}
		} else if (goal < 60 && eIsland != null) {
			// attack enemy islands
			rc.setIndicatorString("ENEMY ISLAND");
			if (moveToLocation(rc, eIsland.loc, 0) != MoveStatus.FAILED) {
				int friendlyLaunchers = 0;
				for (RobotInfo ri : nearbyRobots) {
					if (ri.team == rc.getTeam() && ri.type == RobotType.LAUNCHER) {
						friendlyLaunchers++;
					}
				}
				if (friendlyLaunchers < 4) {
					// if less than 4 other robots are present -> stay
					launcherIsStationed = true;
				}
			}
		} else {
			// swarm enemy hq
			if (eHq != null) {
				// go to enemy hq
				rc.setIndicatorString("HQ");
				if (moveToLocation(rc, eHq, 3) != MoveStatus.FAILED) {
					launcherIsStationed = true;
				}
			} else {
				// go to a symmetric location
				rc.setIndicatorString("HQ Guess");
				if (moveToLocation(rc, eHqGuess, 3) == MoveStatus.ADJACENT) {
					if (rc.canSenseLocation(eHqGuess)) {
						RobotInfo r = rc.senseRobotAtLocation(eHqGuess);
						if (r != null && r.team == rc.getTeam().opponent() && r.type == RobotType.HEADQUARTERS) {
							launcherIsStationed = true;
						}
					}
				}
			}
		}
	}

	static void runAmplifier(RobotController rc) throws GameActionException {
		readAllInformation(rc);
		updateSharedArrays(rc);

		// go to symmetry by island, symmetry by well or symmetry by HQs, or random
		// position
		int rand = rng.nextInt(4);
		switch (rand) {
			case 0:
				moveToLocation(rc, getSymmetricLocation(rc, randomHq(false)), 0);
				break;
			case 1:
				final MyWellInfo ad = randomWell(ResourceType.ADAMANTIUM);
				final MyWellInfo mn = randomWell(ResourceType.MANA);
				MyWellInfo random = rng.nextInt(2) < 1 ? ad : mn;
				if (random == null) {
					random = ad;
				}
				moveToLocation(rc, getSymmetricLocation(rc, random.loc), 0);
				break;
			case 2:
				MyIslandInfo i = randomIsland(rc.getTeam());
				if (i != null) {
					moveToLocation(rc, getSymmetricLocation(rc, i.loc), 0);
					break;
				} // otherwise go to default case
			default:
				// go to random position to scout
				moveToLocation(rc,
						new MapLocation(rng.nextInt(rc.getMapWidth() / 6) * 6, rng.nextInt(rc.getMapHeight() / 6) * 6), 0);
				break;
		}
	}

	static void runDestabilizer(RobotController rc) throws GameActionException {
	}

	static void runBooster(RobotController rc) throws GameActionException {
	}
}