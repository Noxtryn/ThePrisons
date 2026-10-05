package io.theprisons.modules.mining.ore;

import io.theprisons.core.control.RotationMath;
import io.theprisons.core.nav.Cell;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import io.theprisons.modules.mining.ore.route.RouteCorridor;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.LongToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * The ore macro's steering as of the 30.09. build (the version that walked best): every few ticks the best direction
 * with ore (24 rays) becomes an <b>axis</b> - a long straight line - which is then walked like a recorded route: the
 * richest 5-wide strip up to 5 blocks beside it is the lane, the way is chosen per tick among 24 rays (the held one
 * kept unless another scores 1.3x), back is all but ruled out, and a soft correction (at most 25°) keeps it in the
 * middle of the tunnel. Every 10 ticks a direction aside with twice the score and 6+ ores takes over. Rays may drop off
 * a ledge when at least 4 ores follow.
 *
 * <p>Only additions to the original: rays end at the edge of the guarded area ({@link GuardArea}), and the decision
 * says whether ore lies within {@value #ORE_AHEAD} blocks ahead (the module's stone-block rule). A planned route is
 * walked as the guide line (like a recorded route).</p>
 */
public final class ClassicSteer {
   static final int RAYS = 24;
   static final double STEP_DEGREES = 15.0D;
   static final double RANGE = 32.0D;
   public static final double WARDEN_DISTANCE = 15.0D;
   private static final double WARDEN_HEIGHT = 64.0D;
   private static final double FOREIGN_MARGIN = 2.0D;
   private static final double OPEN_WAY = 8.0D;
   private static final double SAMPLE = 0.5D;
   private static final double HALF_BODY = 0.35;
   static final double SWITCH_FACTOR = 1.3;
   private static final double MIN_FREE = 1.5D;
   private static final double EMPTY_VALUE = 0.05;
   private static final double ORE_HALF_DISTANCE = 16.0D;
   private static final double CENTRE_LOOKAHEAD = 3.0D;
   private static final double MAX_CENTRE_DEGREES = 25.0D;
   private static final double OPEN_ROOM = 6.0D;
   private double held = Double.NaN;
   private double centring;
   private static final double CENTRE_SMOOTHING = 0.25D;
   private double blockedHeading = Double.NaN;
   private long blockedUntil;
   private BorderZones borders = new BorderZones();
   /** The guarded area (an empty one = no limit): rays end at its edge. */
   private GuardArea guards = new GuardArea();
   /** The tunnel's floor and walls around the player (centring). */
   private final TunnelMap map = new TunnelMap();
   private boolean wallHit;
   /** The new axis after a wall hit turned back (more than 90 degrees). */
   private boolean wallTurnedBack;
   /** How often each block was entered (the latest {@value #VISIT_MEMORY} entries). */
   private final Long2IntOpenHashMap visits = new Long2IntOpenHashMap();
   private final LongArrayFIFOQueue visitOrder = new LongArrayFIFOQueue();
   private long lastVisit = Long.MIN_VALUE;
   /** The last {@value #COURSE_BLOCKS} blocks entered (x, z), for the course: the stretch is walked on, not back. */
   private final double[] courseX = new double[COURSE_BLOCKS];
   private final double[] courseZ = new double[COURSE_BLOCKS];
   private int courseCount;
   static final int COURSE_BLOCKS = 12;
   /** Rays this far off the course count a little less; past {@value #COURSE_BACK} they are a last resort (user: one stretch, no return). */
   static final double COURSE_SIDE = 60.0D;
   static final double COURSE_BACK = 100.0D;
   /** The middle is kept between walls up to this far to each side (a wider room: no centring). */
   static final double CENTRE_RANGE = 16.0D;
   /** Score / lane yield factor per step up: the flat way first when there is one (additions to the original). */
   static final double RISE_FACTOR = 0.9D;
   /** The axis is chosen anew when less than this is free ahead on it - turning in time, not at the wall. */
   static final double AXIS_KEEP_FREE = 6.0D;
   /** Ore within this many blocks ahead on the chosen way (for the module's stone-block rule). */
   static final double ORE_AHEAD = 10.0D;
   /** Block entries remembered for the visit count (a sliding window of the latest steps). */
   static final int VISIT_MEMORY = 3000;
   /**
    * A way walked once within the latest {@value #VISIT_MEMORY} steps is worth this (walking it a second time is
    * normal); every further time {@value #REPEAT_STEEP} of that: no staying at one spot, mining spreads over the mine.
    */
   static final double REPEAT_ONCE = 0.7D;
   static final double REPEAT_STEEP = 0.35D;
   private int @Nullable [] guideFrom;
   private int @Nullable [] guideTo;
   static final double GUIDE_DEGREES = 40.0D;
   private double guideLane = Double.NaN;
   static final double LANE_SWITCH = 1.5D;
   static final double BACK_DEGREES = 90.0D;
   static final double LANE_KEEP = 2.0D;
   private static final double LANE_LOOKAHEAD = 4.0D;
   static final int LANE_AHEAD = 10;
   static final int MINE_HALF = 2;
   static final int LANE_MIN_WALK = 3;
   static final double LANE_GAIN = 0.9;
   static final double LANE_GAIN_RATIO = 1.1;
   private double targetLane = Double.NaN;
   private int @Nullable [] axisFrom;
   private int @Nullable [] axisTo;
   static final double AXIS_MIN_FREE = 3.0D;
   private static final double AXIS_LENGTH = 1000.0D;
   static final int CLUSTER_RADIUS = 2;
   static final double CLUSTER_NORM = 4.0D;
   static final double CLUSTER_SWITCH = 2.0D;
   static final double CLUSTER_MIN_ORE = 6.0D;
   static final int CLUSTER_CHECK_TICKS = 10;
   private int clusterCheck;
   static final int MAX_DROP = 32;
   static final double DROP_MIN_ORE = 4.0D;
   private final Long2IntOpenHashMap clusters = new Long2IntOpenHashMap();

   public void guide(int @Nullable [] from, int @Nullable [] to) {
      if (!Arrays.equals(from, this.guideFrom) || !Arrays.equals(to, this.guideTo)) {
         this.guideLane = Double.NaN;
         this.targetLane = Double.NaN;
      }

      this.guideFrom = from;
      this.guideTo = to;
      if (to != null) {
         this.clearAxis();
      }

   }

   private int @Nullable [] lineFrom() {
      return this.guideTo != null ? this.guideFrom : this.axisFrom;
   }

   private int @Nullable [] lineTo() {
      return this.guideTo != null ? this.guideTo : this.axisTo;
   }

   private void clearAxis() {
      if (this.axisTo != null) {
         this.guideLane = Double.NaN;
         this.targetLane = Double.NaN;
      }

      this.axisFrom = null;
      this.axisTo = null;
   }

   private void setAxis(double heading, double x, double feetY, double z) {
      double rad = Math.toRadians(heading);
      int bx = (int)Math.floor(x);
      int bz = (int)Math.floor(z);
      this.axisFrom = new int[]{bx, (int)Math.floor(feetY + 0.01), bz};
      this.axisTo = new int[]{bx + (int)Math.round(-Math.sin(rad) * 1000.0D), this.axisFrom[1], bz + (int)Math.round(Math.cos(rad) * 1000.0D)};
      this.guideLane = 0.0D;
      this.targetLane = Double.NaN;
      this.held = Double.NaN;
   }

   private void updateAxis(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x, double feetY, double z, float yaw, LongSet walked, LongToDoubleFunction zoneFactor, boolean avoid) {
      if (this.guideTo == null) {
         int[] from = this.axisFrom;
         int[] to = this.axisTo;
         double along = Double.NaN;
         if (from != null && to != null) {
            along = (double)RotationMath.yawOf((double)(to[0] - from[0]), (double)(to[2] - from[2]));
            boolean inside = Math.abs(RouteCorridor.side(from, to, x, z)) <= 5.0D;
            boolean blocked = avoid && (double)Math.abs(RotationMath.wrap((float)(along - this.blockedHeading))) < 30.0D;
            if (inside && !blocked) {
               this.axisFrom = null;
               this.axisTo = null;
               Ray straight = this.cast(view, walk, isTarget, isForeign, x, feetY, z, along, walked);
               this.axisFrom = from;
               this.axisTo = to;
               if (straight.free() >= AXIS_KEEP_FREE && !this.richerAside(view, walk, isTarget, isForeign, x, feetY, z, along, straight, walked, zoneFactor, avoid)) {
                  return;
               }
               if (straight.free() < AXIS_KEEP_FREE) {
                  // The axis runs into a wall: the module may look for a way round or up it (a stair, a side tunnel).
                  this.wallHit = true;
               }
            }
         }

         this.axisFrom = null;
         this.axisTo = null;
         double reference = Double.isNaN(along) ? (double)yaw : along;
         double bestHeading = Double.NaN;
         double bestScore = -Double.MAX_VALUE;
         double backHeading = Double.NaN;
         double backScore = -Double.MAX_VALUE;

         for(int i = 0; i < 24; ++i) {
            double heading = reference + (double)i * 15.0D;
            Ray ray = this.cast(view, walk, isTarget, isForeign, x, feetY, z, heading, walked);
            if (!(ray.free() < 3.0D)) {
               double turn = (double)Math.abs(RotationMath.wrap((float)(heading - reference)));
               double score = this.axisScore(ray, heading, turn, x, feetY, z, zoneFactor, avoid);
               if (turn <= 90.0D) {
                  if (score > bestScore) {
                     bestScore = score;
                     bestHeading = heading;
                  }
               } else if (score > backScore) {
                  backScore = score;
                  backHeading = heading;
               }
            }
         }

         double chosen = !Double.isNaN(bestHeading) ? bestHeading : backHeading;
         if (this.wallHit) {
            this.wallTurnedBack = Double.isNaN(chosen) || Math.abs(RotationMath.wrap((float)(chosen - reference))) > 90.0F;
         }
         if (!Double.isNaN(chosen)) {
            this.setAxis((double)RotationMath.wrap((float)chosen), x, feetY, z);
         } else {
            this.guideLane = Double.NaN;
            this.targetLane = Double.NaN;
         }

      }
   }

   private double axisScore(Ray ray, double heading, double turn, double x, double feetY, double z, LongToDoubleFunction zoneFactor, boolean avoid) {
      int ex = (int)Math.floor(x - Math.sin(Math.toRadians(heading)) * Math.min(ray.free(), 4.0D));
      int ez = (int)Math.floor(z + Math.cos(Math.toRadians(heading)) * Math.min(ray.free(), 4.0D));
      double score = Math.min(ray.free(), 8.0D) / 8.0D * (ray.ore() + 0.05 * ray.free()) * turnFactor(turn)
              * Math.pow(RISE_FACTOR, (double)ray.rises()) * repeatFactor(ray) * zoneFactor.applyAsDouble(LanePlanner.zoneOf(Pos.pack(ex, (int)Math.floor(feetY), ez)));
      if (avoid && (double)Math.abs(RotationMath.wrap((float)(heading - this.blockedHeading))) < 30.0D) {
         score *= 0.05;
      }

      return score;
   }

   private boolean richerAside(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x, double feetY, double z, double along, Ray straight, LongSet walked, LongToDoubleFunction zoneFactor, boolean avoid) {
      if (++this.clusterCheck < 10) {
         return false;
      } else {
         this.clusterCheck = 0;
         int[] from = this.axisFrom;
         int[] to = this.axisTo;
         this.axisFrom = null;
         this.axisTo = null;

         try {
            double straightScore = this.axisScore(straight, along, 0.0D, x, feetY, z, zoneFactor, avoid);

            for(int i = 1; i < 24; ++i) {
               double heading = along + (double)i * 15.0D;
               double turn = (double)Math.abs(RotationMath.wrap((float)(heading - along)));
               if (!(turn > 90.0D)) {
                  Ray ray = this.cast(view, walk, isTarget, isForeign, x, feetY, z, heading, walked);
                  if (ray.free() >= 3.0D && ray.ore() >= 6.0D && this.axisScore(ray, heading, turn, x, feetY, z, zoneFactor, avoid) >= 2.0D * Math.max(straightScore, 0.05)) {
                     boolean var27 = true;
                     return var27;
                  }
               }
            }

            boolean var31 = false;
            return var31;
         } finally {
            this.axisFrom = from;
            this.axisTo = to;
         }
      }
   }

   private int cluster(VoxelView view, IntPredicate isTarget, int x, int y, int z) {
      long key = Pos.pack(x, y, z);
      int cached = this.clusters.getOrDefault(key, -1);
      if (cached >= 0) {
         return cached;
      } else {
         int n = 0;

         for(int dx = -2; dx <= 2; ++dx) {
            for(int dz = -2; dz <= 2; ++dz) {
               for(int dy = -1; dy <= 1; ++dy) {
                  if ((dx != 0 || dy != 0 || dz != 0) && LanePlanner.surfaceOre(view, isTarget, x + dx, y + dy, z + dz)) {
                     ++n;
                  }
               }
            }
         }

         this.clusters.put(key, n);
         return n;
      }
   }

   public void guards(GuardArea area) {
      this.guards = area;
   }

   /** The axis ran into a wall since the last call (once). */
   public boolean takeWallHit() {
      boolean hit = this.wallHit;
      this.wallHit = false;
      return hit;
   }

   /** After the last wall hit the new axis turned back (more than 90 degrees): the way ahead looked closed. */
   public boolean wallTurnedBack() {
      return this.wallTurnedBack;
   }

   /** Counts the block the player stands in (once per block entered). */
   private void visit(double x, double feetY, double z) {
      long node = Pos.pack((int)Math.floor(x), (int)Math.floor(feetY + 0.01), (int)Math.floor(z));
      if (node == this.lastVisit) {
         return;
      }
      this.lastVisit = node;
      this.courseX[this.courseCount % COURSE_BLOCKS] = x;
      this.courseZ[this.courseCount % COURSE_BLOCKS] = z;
      ++this.courseCount;
      this.visits.addTo(node, 1);
      this.visitOrder.enqueue(node);
      if (this.visitOrder.size() > VISIT_MEMORY) {
         long old = this.visitOrder.dequeueLong();
         if (this.visits.addTo(old, -1) <= 1) {
            this.visits.remove(old);
         }
      }
   }

   /** Times the player walked over (x, y, z) or a block beside it (a lane off the middle is the same way). */
   private int visitsAt(int x, int y, int z, double lx, double lz) {
      int v = this.visits.get(Pos.pack(x, y, z));
      v = Math.max(v, this.visits.get(Pos.pack((int)Math.floor(x + 0.5D + lx), y, (int)Math.floor(z + 0.5D + lz))));
      return Math.max(v, this.visits.get(Pos.pack((int)Math.floor(x + 0.5D - lx), y, (int)Math.floor(z + 0.5D - lz))));
   }

   /** Recently walked (overlap) and walked many times before (repeat): fresh ways first. */
   private static double repeatFactor(Ray ray) {
      double r = ray.repeat();
      double repeat = r <= 1.0D ? 1.0D - (1.0D - REPEAT_ONCE) * r : REPEAT_ONCE * Math.pow(REPEAT_STEEP, r - 1.0D);
      return (1.0D - 0.8 * ray.overlap()) * repeat;
   }

   /** The held heading ({@code NaN} = none yet), for the module's trace line. */
   public String debugState() {
      return Double.isNaN(this.held) ? "classic, no way yet" : String.format(java.util.Locale.ROOT, "classic way %.0f°%s", this.held,
              this.lineTo() == null ? "" : this.guideTo != null ? " (route guide)" : " (axis)");
   }

   public void borders(BorderZones zones) {
      this.borders = zones;
   }

   /**
    * The direction walked over the last {@value #COURSE_BLOCKS} blocks (yaw), NaN until at least 6 blocks away from
    * there. Free steering and the planner keep to it.
    */
   public double course() {
      if (this.courseCount < 6) {
         return Double.NaN;
      }
      int last = (this.courseCount - 1) % COURSE_BLOCKS;
      int first = this.courseCount >= COURSE_BLOCKS ? this.courseCount % COURSE_BLOCKS : 0;
      double dx = this.courseX[last] - this.courseX[first];
      double dz = this.courseZ[last] - this.courseZ[first];
      return Math.hypot(dx, dz) < 6.0D ? Double.NaN : (double)RotationMath.yawOf(dx, dz);
   }

   /** A new stretch (after /warp, a chore, travel): the old course no longer counts. */
   public void clearCourse() {
      this.courseCount = 0;
   }

   public void reset() {
      this.clearAxis();
      this.clusterCheck = 0;
      this.held = Double.NaN;
      this.centring = 0.0D;
      this.blockedHeading = Double.NaN;
      this.blockedUntil = 0L;
   }

   public void block(double heading, long untilMs) {
      this.blockedHeading = heading;
      this.blockedUntil = untilMs;
      this.held = Double.NaN;
      this.clearAxis();
   }

   public TunnelSteer.Decision decide(VoxelView view, IntPredicate isTarget, double x, double feetY, double z, float yaw, boolean onGround, LongSet walked, LongToDoubleFunction zoneFactor, long nowMs) {
      return this.decide(view, isTarget, (key) -> false, x, feetY, z, yaw, onGround, walked, zoneFactor, new double[0][], nowMs);
   }

   public TunnelSteer.Decision decide(VoxelView view, IntPredicate isTarget, IntPredicate isForeign, double x, double feetY, double z, float yaw, boolean onGround, LongSet walked, LongToDoubleFunction zoneFactor, double[][] wardens, long nowMs) {
      boolean avoid = !Double.isNaN(this.blockedHeading) && nowMs < this.blockedUntil;
      Walkability walk = new Walkability(view, 3);
      this.clusters.clear();
      this.visit(x, feetY, z);
      this.updateAxis(view, walk, isTarget, isForeign, x, feetY, z, yaw, walked, zoneFactor, avoid);
      this.updateLane(x, z);
      this.chooseLane(view, walk, isTarget, x, feetY, z);
      double reference = Double.isNaN(this.held) ? (double)yaw : this.held;
      double course = this.course();
      Ray best = null;
      double bestScore = -Double.MAX_VALUE;
      Ray kept = null;
      double keptScore = -Double.MAX_VALUE;
      boolean forwardOpen = false;

      for(int i = 0; i < 24; ++i) {
         double heading = reference + (double)i * 15.0D;
         if (!this.goesBack(heading)) {
            Ray ray = this.cast(view, walk, isTarget, isForeign, x, feetY, z, heading, walked);
            if (!(ray.free() < 1.5D)) {
               double turn = (double)Math.abs(RotationMath.wrap((float)(heading - reference)));
               if (turn <= 90.0D) {
                  forwardOpen = true;
               }

               int ex = (int)Math.floor(x - Math.sin(Math.toRadians(heading)) * Math.min(ray.free(), 4.0D));
               int ez = (int)Math.floor(z + Math.cos(Math.toRadians(heading)) * Math.min(ray.free(), 4.0D));
               double score = Math.min(ray.free(), 8.0D) / 8.0D * (ray.ore() + 0.05 * ray.free()) * turnFactor(turn) * (this.guideTo != null ? 1.0D : Math.pow(RISE_FACTOR, (double)ray.rises())) * repeatFactor(ray) * zoneFactor.applyAsDouble(LanePlanner.zoneOf(Pos.pack(ex, (int)Math.floor(feetY), ez)));
               score *= this.directionFactor(heading);
               if (avoid && (double)Math.abs(RotationMath.wrap((float)(heading - this.blockedHeading))) < 30.0D) {
                  score *= 0.05;
               }

               score *= this.laneFactor(heading, x, z);
               if (this.guideTo == null && (double)Math.abs(RotationMath.wrap((float)(heading - (double)yaw))) > 90.0D) {
                  score *= 0.01;
               }

               if (this.guideTo == null && !Double.isNaN(course)) {
                  double off = (double)Math.abs(RotationMath.wrap((float)(heading - course)));
                  if (off > COURSE_BACK) {
                     score *= 0.001;
                  } else if (off > COURSE_SIDE) {
                     score *= 0.3;
                  }
               }

               if (score > bestScore) {
                  bestScore = score;
                  best = ray;
               }

               if (turn < 7.5D && !Double.isNaN(this.held)) {
                  kept = ray;
                  keptScore = score;
               }
            }
         }
      }

      if (best == null) {
         return new TunnelSteer.Decision(yaw, false, false, 0.0D, 0.0D, yaw, new double[0], new double[0], true, false);
      } else {
         Ray chosen = kept != null && keptScore * 1.3 >= bestScore ? kept : best;
         this.held = (double)RotationMath.wrap((float)chosen.heading());
         float steer = this.centre(walk, x, feetY, z, chosen.heading());
         boolean jump = onGround && chosen.feet().length > 1 && chosen.feet()[0] > feetY + 0.5D && chosen.dist()[0] < 1.2 || onGround && chosen.feet().length > 2 && chosen.feet()[1] > feetY + 0.5D && chosen.dist()[1] < 1.2;
         return new TunnelSteer.Decision(steer, true, jump, chosen.free(), chosen.ore(), (float)this.held, chosen.feet(), chosen.dist(), !forwardOpen,
               chosen.free() >= 1.5D && chosen.firstOre() <= ORE_AHEAD);
      }
   }

   private boolean leavesGuide(double px, double pz, double x, double z) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double side = RouteCorridor.project(from, to, px, pz)[1];
         return side > 5.0D && side > RouteCorridor.project(from, to, x, z)[1] + 0.05;
      } else {
         return false;
      }
   }

   private void updateLane(double x, double z) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double side = RouteCorridor.side(from, to, x, z);
         if (Math.abs(side) > 5.0D) {
            this.guideLane = Math.signum(side) * 4.0D;
         } else if (!(Math.abs(side) <= 1.5D) || !Double.isNaN(this.guideLane) && !(Math.abs(this.guideLane) <= 1.5D)) {
            if (Double.isNaN(this.guideLane) || Math.abs(side - this.guideLane) > 1.5D) {
               this.guideLane = side;
            }
         } else {
            this.guideLane = 0.0D;
         }

      }
   }

   private boolean goesBack(double heading) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double along = (double)RotationMath.yawOf((double)(to[0] - from[0]), (double)(to[2] - from[2]));
         return (double)Math.abs(RotationMath.wrap((float)(heading - along))) > 90.0D;
      } else {
         return false;
      }
   }

   private double directionFactor(double heading) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double along = (double)RotationMath.yawOf((double)(to[0] - from[0]), (double)(to[2] - from[2]));
         double f = (double)Math.abs(RotationMath.wrap((float)(heading - along))) / 40.0D;
         return 1.0D / (1.0D + f * f);
      } else {
         return 1.0D;
      }
   }

   private double laneFactor(double heading, double x, double z) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double rad = Math.toRadians(heading);
         double ahead = RouteCorridor.side(from, to, x - Math.sin(rad) * 4.0D, z + Math.cos(rad) * 4.0D);
         double lane = !Double.isNaN(this.targetLane) ? this.targetLane : (Double.isNaN(this.guideLane) ? 0.0D : this.guideLane);
         double g = (ahead - lane) / 2.0D;
         return 1.0D / (1.0D + g * g);
      } else {
         return 1.0D;
      }
   }

   private void chooseLane(VoxelView view, Walkability walk, IntPredicate isTarget, double x, double feetY, double z) {
      int[] from = this.lineFrom();
      int[] to = this.lineTo();
      if (from != null && to != null) {
         double side = RouteCorridor.side(from, to, x, z);
         double progress = RouteCorridor.project(from, to, x, z)[0];
         if (!Double.isNaN(this.targetLane) && Math.abs(side - this.targetLane) <= 1.5D) {
            this.guideLane = side;
            this.targetLane = Double.NaN;
            if (this.guideTo == null) {
               this.setAxis((double)RotationMath.yawOf((double)(to[0] - from[0]), (double)(to[2] - from[2])), x, feetY, z);
               from = this.axisFrom;
               to = this.axisTo;
               side = RouteCorridor.side(from, to, x, z);
               progress = RouteCorridor.project(from, to, x, z)[0];
            }
         }

         int current = (int)Math.rint(Double.isNaN(this.targetLane) ? side : this.targetLane);
         Strip here = strip(view, walk, isTarget, from, to, progress, current, feetY);
         double hereYield = here.walkable() ? oreYield(here, (double)current - side) * flatness(here) : 0.0D;
         int max = 5;
         double bestYield = hereYield;
         int best = current;

         for(int lane = -max; lane <= max; ++lane) {
            if (lane != current) {
               Strip s = strip(view, walk, isTarget, from, to, progress, lane, feetY);
               if (s.walkable()) {
                  double y = oreYield(s, (double)lane - side) * flatness(s);
                  if (y > bestYield || y == bestYield && best != current && Math.abs((double)lane - side) < Math.abs((double)best - side)) {
                     bestYield = y;
                     best = lane;
                  }
               }
            }
         }

         if (best != current && bestYield >= hereYield + 0.9 && bestYield >= hereYield * 1.1) {
            this.targetLane = (double)best;
         }

      }
   }

   /** Flat lanes first: each step up on the lane's line costs (not on a recorded / planned route - it goes up stairs). */
   private double flatness(Strip strip) {
      return this.guideTo != null ? 1.0D : Math.pow(RISE_FACTOR, strip.rises());
   }

   static double oreYield(Strip strip, double shift) {
      return (double)(strip.ore() * 10) / Math.hypot(10.0D, shift);
   }

   static Strip strip(VoxelView view, Walkability walk, IntPredicate isTarget, int[] from, int[] to, double progress, int lane, double feetY) {
      double ax = (double)from[0] + 0.5D;
      double az = (double)from[2] + 0.5D;
      double dx = (double)to[0] + 0.5D - ax;
      double dz = (double)to[2] + 0.5D - az;
      double len = Math.hypot(dx, dz);
      if (len < 1.0E-6) {
         return new Strip(0, 0, false, 0);
      } else {
         double ux = dx / len;
         double uz = dz / len;
         int[] height = new int[5];
         Arrays.fill(height, (int)Math.floor(feetY + 0.01));
         int stone = 0;
         int ore = 0;
         int walkedOn = 0;
         int rises = 0;

         for(int t = 1; t <= 10; ++t) {
            double along = progress + (double)t;
            int mx = (int)Math.floor(ax + ux * along + uz * (double)lane);
            int mz = (int)Math.floor(az + uz * along - ux * (double)lane);
            int lineY = standY(walk, mx, height[2], mz);
            if (lineY == Integer.MIN_VALUE) {
               break;
            }
            if (lineY > height[2]) {
               ++rises;
            }

            walkedOn = t;

            for(int w = -2; w <= 2; ++w) {
               double s = (double)(lane + w);
               int bx = (int)Math.floor(ax + ux * along + uz * s);
               int bz = (int)Math.floor(az + uz * along - ux * s);
               int found = standY(walk, bx, height[w + 2], bz);
               if (found != Integer.MIN_VALUE) {
                  height[w + 2] = found;
                  int key = view.ore(bx, found - 1, bz);
                  if (key != 0 && isTarget.test(key)) {
                     ++ore;
                  } else {
                     ++stone;
                  }
               }
            }
         }

         return new Strip(stone, ore, walkedOn >= 3, rises);
      }
   }

   Ray cast(VoxelView view, Walkability walk, IntPredicate isTarget, double x, double feetY, double z, double heading, LongSet walked) {
      return this.cast(view, walk, isTarget, (key) -> false, x, feetY, z, heading, walked);
   }

   Ray cast(VoxelView view, Walkability walk, IntPredicate isTarget, IntPredicate isForeign, double x, double feetY, double z, double heading, LongSet walked) {
      double foreignAt = Double.POSITIVE_INFINITY;
      double rad = Math.toRadians(heading);
      double fx = -Math.sin(rad);
      double fz = Math.cos(rad);
      double lx = -fz;
      double lz = fx;
      int height = (int)Math.floor(feetY + 0.01);
      int lastX = (int)Math.floor(x);
      int lastZ = (int)Math.floor(z);
      double free = 0.0D;
      int rises = 0;
      int overlap = 0;
      int columns = 0;
      int repeatSum = 0;
      int repeatColumns = 0;
      LongOpenHashSet counted = new LongOpenHashSet();
      double ore = 0.0D;
      double firstOre = Double.POSITIVE_INFINITY;
      int samples = 64;
      double[] feet = new double[samples];
      double[] dist = new double[samples];
      int n = 0;
      int dropAt = -1;
      double freeBeforeDrop = 0.0D;
      double oreBeforeDrop = 0.0D;

      for(int s = 1; s <= samples; ++s) {
         double d = (double)s * 0.5D;
         double px = x + fx * d;
         double pz = z + fz * d;
         int cx = (int)Math.floor(px);
         int cz = (int)Math.floor(pz);
         if (this.borders.blocks(px, feetY, pz, x, z) || this.leavesGuide(px, pz, x, z)) {
            break;
         }

         int found = standY(walk, cx, height, cz);
         if (found == Integer.MIN_VALUE && dropAt < 0 && (cx != lastX || cz != lastZ)) {
            found = dropY(walk, cx, height, cz);
            if (found != Integer.MIN_VALUE) {
               dropAt = n;
               freeBeforeDrop = free;
               oreBeforeDrop = ore;
            }
         }

         if (found == Integer.MIN_VALUE || !bodyFits(walk, px, pz, lx, lz, found) || this.guards.blocks(px, found, pz, x, feetY, z)) {
            break;
         }

         if (cx != lastX || cz != lastZ) {
            if (found > height) {
               ++rises;
            }

            ++columns;
            if (walked.contains(Pos.pack(cx, found, cz))) {
               ++overlap;
            }

            if (repeatColumns < 16) {
               repeatSum += this.visitsAt(cx, found, cz, lx, lz);
               ++repeatColumns;
            }

            if (seesForeign(view, isForeign, px, pz, lx, lz, found)) {
               foreignAt = d;
               break;
            }

            for(int w = -2; w <= 2; ++w) {
               int bx = (int)Math.floor(px + lx * (double)w);
               int bz = (int)Math.floor(pz + lz * (double)w);

               for(int by = found - 3; by <= found; ++by) {
                  long pos = Pos.pack(bx, by, bz);
                  if (!counted.contains(pos) && LanePlanner.surfaceOre(view, isTarget, bx, by, bz)) {
                     counted.add(pos);
                     if (Math.abs(w) <= 1) {
                        firstOre = Math.min(firstOre, d);
                     }
                     ore += LanePlanner.lateralWeight(w) / (1.0D + d / 16.0D) * (1.0D + (double)this.cluster(view, isTarget, bx, by, bz) / 4.0D);
                  }
               }
            }

            lastX = cx;
            lastZ = cz;
            height = found;
         }

         feet[n] = walk.standHeight(cx, found, cz);
         dist[n] = d;
         ++n;
         free = d;
      }

      if (dropAt >= 0 && ore - oreBeforeDrop < 4.0D) {
         free = freeBeforeDrop;
         ore = oreBeforeDrop;
         n = dropAt;
      }

      if (foreignAt < Double.POSITIVE_INFINITY) {
         free = Math.max(0.0D, Math.min(free, foreignAt - 2.0D));

         int keep;
         for(keep = 0; keep < n && dist[keep] <= free; ++keep) {
         }

         n = keep;
      }

      return new Ray(heading, free, ore, rises, columns == 0 ? 0.0D : (double)overlap / (double)columns, Arrays.copyOf(feet, n), Arrays.copyOf(dist, n), firstOre,
              repeatColumns == 0 ? 0.0D : (double)repeatSum / (double)repeatColumns);
   }

   private static double limitRayToWardens(double[][] wardens, double x, double feetY, double z, double heading, double maxDistance) {
      if (wardens != null && wardens.length != 0) {
         boolean hasValidWarden = false;

         for(double[] warden : wardens) {
            if (warden != null && warden.length >= 3 && !(Math.abs(warden[1] - feetY) > 64.0D)) {
               hasValidWarden = true;
               break;
            }
         }

         if (!hasValidWarden) {
            return maxDistance;
         } else {
            double rad = Math.toRadians(heading);
            double dx = -Math.sin(rad);
            double dz = Math.cos(rad);
            double previousDistance = 0.0D;
            boolean previousInside = insideAnyWardenRange(wardens, x, feetY, z, heading, 0.0D);
            if (!previousInside) {
               double step = 0.25D;

               for(double d = step; d <= maxDistance; d += step) {
                  if (insideAnyWardenRange(wardens, x, feetY, z, heading, d)) {
                     return Math.max(0.0D, d - step);
                  }
               }

               return maxDistance;
            } else {
               double step = 0.25D;

               for(double d = Math.max(step, previousDistance + step); d <= maxDistance; d += step) {
                  boolean inside = insideAnyWardenRange(wardens, x, feetY, z, heading, d);
                  if (!inside) {
                     double low = d - step;
                     double high = d;

                     for(int i = 0; i < 8; ++i) {
                        double mid = (low + high) * 0.5D;
                        if (insideAnyWardenRange(wardens, x, feetY, z, heading, mid)) {
                           low = mid;
                        } else {
                           high = mid;
                        }
                     }

                     return low;
                  }
               }

               return maxDistance;
            }
         }
      } else {
         return maxDistance;
      }
   }

   private static boolean insideAnyWardenRange(double[][] wardens, double x, double feetY, double z, double heading, double distance) {
      double rad = Math.toRadians(heading);
      double px = x - Math.sin(rad) * distance;
      double pz = z + Math.cos(rad) * distance;
      return insideAnyWardenRange(wardens, px, feetY, pz);
   }

   private static boolean insideAnyWardenRange(double[][] wardens, double x, double feetY, double z) {
      if (wardens != null && wardens.length != 0) {
         for(double[] warden : wardens) {
            if (warden != null && warden.length >= 3 && !(Math.abs(warden[1] - feetY) > 64.0D)) {
               double distance = Math.hypot(warden[0] - x, warden[2] - z);
               if (distance <= 15.0D) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private static Ray limitRayToDistance(Ray ray, double maxDistance) {
      if (maxDistance >= ray.free()) {
         return ray;
      } else {
         int n;
         for(n = 0; n < ray.dist().length && ray.dist()[n] <= maxDistance; ++n) {
         }

         return new Ray(ray.heading(), maxDistance, ray.ore(), ray.rises(), ray.overlap(), Arrays.copyOf(ray.feet(), n), Arrays.copyOf(ray.dist(), n), ray.firstOre(), ray.repeat());
      }
   }

   private static boolean seesForeign(VoxelView view, IntPredicate isForeign, double px, double pz, double lx, double lz, int feet) {
      for(int w = -2; w <= 2; ++w) {
         int bx = (int)Math.floor(px + lx * (double)w);
         int bz = (int)Math.floor(pz + lz * (double)w);

         for(int by = feet - 3; by <= feet + 3; ++by) {
            int key = view.ore(bx, by, bz);
            if (key != 0 && isForeign.test(key) && view.exposed(bx, by, bz)) {
               return true;
            }
         }
      }

      return false;
   }

   private float centre(Walkability walk, double x, double feetY, double z, double heading) {
      if (this.guideTo != null || !Double.isNaN(this.targetLane)) {
         this.centring = 0.0D;
         return RotationMath.wrap((float)heading);
      } else {
         double rad = Math.toRadians(heading);
         double fx = -Math.sin(rad);
         double fz = Math.cos(rad);
         double ax = x + fx * 2.0D;
         double az = z + fz * 2.0D;
         int height = (int)Math.floor(feetY + 0.01);
         // The walls by the ground's shape (a fast rise is a wall, a bowl's slope too - TunnelMap), up to 16 blocks.
         this.map.update(walk, (int)Math.floor(x), height, (int)Math.floor(z));
         double left = this.wallDistance(ax, az, fz, -fx);
         double right = this.wallDistance(ax, az, -fz, fx);
         if (left >= CENTRE_RANGE && right >= CENTRE_RANGE) {
            this.centring *= 0.75D;
         } else {
            double offset = (right - left) / 2.0D;
            double correction = Math.toDegrees(Math.atan2(offset, 3.0D));
            correction = Math.max(-25.0D, Math.min(25.0D, correction));
            this.centring += 0.25D * (correction - this.centring);
         }
         // The correction never steers into a block the player cannot walk on (an edge right beside the free way).
         double steered = heading + this.centring;
         double srad = Math.toRadians(steered);
         for (double ahead = 1.0D; ahead <= 1.5D; ahead += 0.5D) {
            if (standY(walk, (int)Math.floor(x - Math.sin(srad) * ahead), height, (int)Math.floor(z + Math.cos(srad) * ahead)) == Integer.MIN_VALUE) {
               this.centring = 0.0D;
               return RotationMath.wrap((float)heading);
            }
         }
         return RotationMath.wrap((float)steered);
      }
   }

   /** Floor blocks from (ax, az) in direction (dx, dz) up to the tunnel wall ({@link TunnelMap}), at most {@value #CENTRE_RANGE}. */
   private double wallDistance(double ax, double az, double dx, double dz) {
      double d = 0.0D;
      while (d < CENTRE_RANGE) {
         double next = d + 0.25D;
         if (!this.map.way(ax + dx * next, az + dz * next)) {
            break;
         }
         d = next;
      }
      return d;
   }

   private static double room(Walkability walk, double ax, double az, double dx, double dz, int height) {
      double d = 0.0D;

      double next;
      for(int h = height; d < 6.0D; d = next) {
         next = d + 0.1;
         int found = standY(walk, (int)Math.floor(ax + dx * next), h, (int)Math.floor(az + dz * next));
         if (found == Integer.MIN_VALUE) {
            break;
         }

         h = found;
      }

      return d;
   }

   private static int dropY(Walkability walk, int x, int height, int z) {
      VoxelView view = walk.view();

      for(int y = height + 1; y > height - 32; --y) {
         int cell = view.cell(x, y, z);
         if (cell == -1 || Cell.hasCollision(cell) || Cell.isLiquid(cell) || Cell.isDanger(cell)) {
            return Integer.MIN_VALUE;
         }

         if (y < height - 3 && walk.isStandable(x, y, z)) {
            return y;
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int standY(Walkability walk, int x, int height, int z) {
      for(int dy : new int[]{0, 1, -1, -2, -3}) {
         if (walk.isStandable(x, height + dy, z)) {
            return height + dy;
         }
      }

      return Integer.MIN_VALUE;
   }

   private static boolean bodyFits(Walkability walk, double px, double pz, double lx, double lz, int height) {
      for(double side : new double[]{-0.35, 0.35}) {
         int sx = (int)Math.floor(px + lx * side);
         int sz = (int)Math.floor(pz + lz * side);
         if (standY(walk, sx, height, sz) == Integer.MIN_VALUE) {
            return false;
         }
      }

      return true;
   }

   static double turnFactor(double degrees) {
      double t = degrees / 90.0D;
      return 1.0D / (1.0D + t * t);
   }


   /** {@code repeat}: mean times the first 16 blocks of the ray were walked before. */
   static record Ray(double heading, double free, double ore, int rises, double overlap, double[] feet, double[] dist, double firstOre, double repeat) {
   }

   /** A lane's strip ahead: stone and ore floor blocks, walkable (3+ blocks), steps up on its line. */
   static record Strip(int stone, int ore, boolean walkable, int rises) {
   }
}
