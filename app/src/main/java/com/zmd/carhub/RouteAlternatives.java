package com.zmd.carhub;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Other ways to the same destination, for the route chooser (2-3 routes, the driver picks one).
 *
 * The offline road network is pre-computed for the single fastest route (contraction
 * hierarchies), which cannot list alternatives by itself. So a different route is forced through
 * a "via" point on a main road to the left or right of the first route. Routes that drive to the
 * via point and turn back are dropped, and of the rest only those that are really different
 * (at most 70% shared with any other route shown) and not much longer (at most 1.4x) are kept.
 *
 * Plain Java with no Android code, so this same file runs on the PC against the real data:
 *   java -cp graphhopper-web-1.0.jar RouteAlternatives.java A:\byd\MapData\build\iraq-gh
 */
public final class RouteAlternatives {
    /** An alternative may be at most this much longer than the main route. */
    public static final double MAX_LONGER = 1.4;
    /** ...and may run along another route shown for at most this share of its length. */
    public static final double MAX_SHARED = 0.7;

    private RouteAlternatives() {
    }

    /**
     * Where the via point goes: how far along the main route (share of its length) and how far to
     * the side (share of the straight-line distance from start to end). Tried in this order, each
     * on the left and on the right.
     */
    private static final double[][] PLAN = {
        {0.50, 0.18}, {0.50, 0.32}, {0.35, 0.22}, {0.65, 0.22}, {0.50, 0.09}, {0.25, 0.14}, {0.75, 0.14},
    };

    /**
     * Up to [want] alternatives to [main] (which must come from the same [hopper]), shortest first.
     * Gives up looking after [budgetMs]. Never throws for "no alternative": the list is just empty.
     */
    public static List<ResponsePath> find(GraphHopper hopper, ResponsePath main, int want, long budgetMs) {
        List<ResponsePath> out = new ArrayList<>();
        if (want <= 0) return out;
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        double[] mp = points(main.getPoints());
        int n = mp.length / 2;
        if (n < 2) return out;
        double la1 = mp[0], lo1 = mp[1], la2 = mp[2 * n - 2], lo2 = mp[2 * n - 1];
        double straight = dist(la1, lo1, la2, lo2);
        if (straight < 600) return out; // around the corner: nothing to choose from
        double[] cum = cumulative(mp);
        double mainLen = cum[n - 1];
        double kx = Math.cos(Math.toRadians((la1 + la2) / 2)) * 111320.0;
        double ky = 110540.0;
        double ux = (lo2 - lo1) * kx / straight;
        double uy = (la2 - la1) * ky / straight;
        double nx = -uy, ny = ux; // to the left, looking from the start towards the end
        EdgeFilter roads = mainRoads(hopper, straight);

        List<ResponsePath> paths = new ArrayList<>();
        List<double[]> shapes = new ArrayList<>();
        List<double[]> tried = new ArrayList<>();
        search:
        for (double[] plan : PLAN) {
            for (int side = 1; side >= -1; side -= 2) {
                if (System.nanoTime() > deadline || paths.size() >= want + 2) break search;
                double[] at = pointAt(mp, cum, plan[0] * mainLen);
                double off = Math.min(60000, Math.max(800, plan[1] * straight));
                GHPoint3D via = null;
                for (double scale : new double[]{1.0, 0.7, 0.45}) {
                    QueryResult qr = hopper.getLocationIndex().findClosest(
                        at[0] + side * off * scale * ny / ky, at[1] + side * off * scale * nx / kx, roads);
                    if (qr.isValid()) {
                        via = qr.getSnappedPoint();
                        break;
                    }
                }
                if (via == null) continue;
                // On (or right next to) the main route: that would only give the main route back.
                if (distToLine(mp, via.lat, via.lon) < Math.max(300, 0.04 * straight)) continue;
                boolean seen = false;
                for (double[] t : tried) {
                    if (dist(t[0], t[1], via.lat, via.lon) < Math.max(400, 0.04 * straight)) {
                        seen = true;
                        break;
                    }
                }
                if (seen) continue;
                tried.add(new double[]{via.lat, via.lon});
                GHResponse rsp;
                try {
                    rsp = hopper.route(new GHRequest(Arrays.asList(
                        new GHPoint(la1, lo1), new GHPoint(via.lat, via.lon), new GHPoint(la2, lo2)))
                        .setProfile("car").setLocale(Locale.ENGLISH));
                } catch (RuntimeException e) {
                    continue;
                }
                if (rsp.hasErrors()) continue;
                ResponsePath p = rsp.getBest();
                if (p.getDistance() > MAX_LONGER * main.getDistance()) continue;
                double[] shape = points(p.getPoints());
                // Drives up to the via point and comes back the same way: not a real route.
                if (spur(shape, viaIndex(p.getInstructions())) > 20) continue;
                paths.add(p);
                shapes.add(shape);
            }
        }

        // Shortest first; each kept one must differ from the main route and from those already kept.
        Integer[] order = new Integer[paths.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> paths.get(i).getDistance()));
        List<double[]> kept = new ArrayList<>();
        kept.add(mp);
        for (int i : order) {
            if (out.size() >= want) break;
            double[] shape = shapes.get(i);
            boolean distinct = true;
            for (double[] k : kept) {
                if (overlap(shape, k) > MAX_SHARED || overlap(k, shape) > MAX_SHARED) {
                    distinct = false;
                    break;
                }
            }
            if (!distinct) continue;
            kept.add(shape);
            out.add(paths.get(i));
        }
        return out;
    }

    /** Only roads a driver would call a main road; on long trips only the big ones. */
    private static EdgeFilter mainRoads(GraphHopper hopper, double straightM) {
        EncodingManager em = hopper.getEncodingManager();
        FlagEncoder car = em.getEncoder("car");
        final DefaultEdgeFilter access = DefaultEdgeFilter.allEdges(car);
        if (!em.hasEncodedValue(RoadClass.KEY)) return access;
        final EnumEncodedValue<RoadClass> rc = em.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        final EnumSet<RoadClass> ok = EnumSet.of(RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY);
        if (straightM < 80000) ok.add(RoadClass.SECONDARY);
        if (straightM < 15000) ok.add(RoadClass.TERTIARY);
        return edge -> access.accept(edge) && ok.contains(edge.get(rc));
    }

    /** Index (in the point list) of the via point, or -1. */
    public static int viaIndex(InstructionList il) {
        int idx = 0;
        for (Instruction in : il) {
            if (in.getSign() == Instruction.REACHED_VIA) return idx;
            idx += in.getLength();
        }
        return -1;
    }

    /** Metres driven past the via point and back along the same road (0 = goes straight through). */
    static double spur(double[] p, int iv) {
        int n = p.length / 2;
        if (iv <= 0 || iv >= n - 1) return 0;
        int j = 1;
        while (iv - j >= 0 && iv + j < n
            && dist(p[2 * (iv - j)], p[2 * (iv - j) + 1], p[2 * (iv + j)], p[2 * (iv + j) + 1]) < 3.0) j++;
        j--;
        double len = 0;
        for (int k = iv - j; k < iv; k++) len += dist(p[2 * k], p[2 * k + 1], p[2 * k + 2], p[2 * k + 3]);
        return len;
    }

    // ------------------------------------------------------------------ geometry

    public static double[] points(PointList pl) {
        int n = pl.size();
        double[] out = new double[n * 2];
        for (int i = 0; i < n; i++) {
            out[2 * i] = pl.getLat(i);
            out[2 * i + 1] = pl.getLon(i);
        }
        return out;
    }

    static double[] cumulative(double[] p) {
        int n = p.length / 2;
        double[] c = new double[Math.max(n, 1)];
        for (int i = 1; i < n; i++) c[i] = c[i - 1] + dist(p[2 * i - 2], p[2 * i - 1], p[2 * i], p[2 * i + 1]);
        return c;
    }

    /** The point [d] metres along the line. */
    static double[] pointAt(double[] p, double[] cum, double d) {
        int n = p.length / 2;
        if (n == 1 || d <= 0) return new double[]{p[0], p[1]};
        if (d >= cum[n - 1]) return new double[]{p[2 * n - 2], p[2 * n - 1]};
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= d) lo = mid; else hi = mid;
        }
        double len = cum[hi] - cum[lo];
        double t = len <= 0 ? 0 : (d - cum[lo]) / len;
        return new double[]{p[2 * lo] + (p[2 * hi] - p[2 * lo]) * t, p[2 * lo + 1] + (p[2 * hi + 1] - p[2 * lo + 1]) * t};
    }

    /** Metres from a point to the nearest part of the line. */
    static double distToLine(double[] p, double lat, double lon) {
        double kx = Math.cos(Math.toRadians(lat)) * 111320.0, ky = 110540.0;
        double best = Double.MAX_VALUE;
        int n = p.length / 2;
        if (n == 1) return dist(p[0], p[1], lat, lon);
        for (int i = 0; i + 1 < n; i++) {
            best = Math.min(best, segDist((lon - p[2 * i + 1]) * kx, (lat - p[2 * i]) * ky,
                (p[2 * i + 3] - p[2 * i + 1]) * kx, (p[2 * i + 2] - p[2 * i]) * ky));
        }
        return best;
    }

    /** Distance from point (px, py) to the segment from (0, 0) to (sx, sy), all in metres. */
    private static double segDist(double px, double py, double sx, double sy) {
        double len2 = sx * sx + sy * sy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, (px * sx + py * sy) / len2));
        double dx = px - t * sx, dy = py - t * sy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Share (0..1) of line [a]'s length that runs along line [b] (within 30 m). Lines are lat, lon, lat, lon... */
    public static double overlap(double[] a, double[] b) {
        double[] cum = cumulative(a);
        double len = cum[cum.length - 1];
        if (len <= 0 || b.length < 4) return 0;
        Grid g = new Grid(b, 100);
        double step = Math.max(25, len / 4000);
        int samples = 0, shared = 0;
        for (double d = step / 2; d < len; d += step) {
            double[] q = pointAt(a, cum, d);
            samples++;
            if (g.near(q[0], q[1], 30)) shared++;
        }
        return samples == 0 ? 0 : (double) shared / samples;
    }

    /** The segments of a line, sorted into squares of [cell] metres for quick "is it near?" questions. */
    public static final class Grid {
        private final double[] p;
        private final double kx, ky, cell;
        private final HashMap<Long, List<Integer>> cells = new HashMap<>();

        public Grid(double[] p, double cell) {
            this.p = p;
            this.cell = cell;
            int n = p.length / 2;
            double lat = 0;
            for (int i = 0; i < n; i++) lat += p[2 * i];
            lat = n > 0 ? lat / n : 0;
            kx = Math.cos(Math.toRadians(lat)) * 111320.0;
            ky = 110540.0;
            for (int i = 0; i + 1 < n; i++) {
                double x1 = p[2 * i + 1] * kx, y1 = p[2 * i] * ky;
                double x2 = p[2 * i + 3] * kx, y2 = p[2 * i + 2] * ky;
                int steps = (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1) / (cell / 2));
                long last = Long.MIN_VALUE;
                for (int s = 0; s <= steps; s++) {
                    double t = steps == 0 ? 0 : (double) s / steps;
                    long key = key((long) Math.floor((x1 + (x2 - x1) * t) / cell), (long) Math.floor((y1 + (y2 - y1) * t) / cell));
                    if (key == last) continue;
                    last = key;
                    List<Integer> list = cells.get(key);
                    if (list == null) {
                        list = new ArrayList<>(4);
                        cells.put(key, list);
                    }
                    if (list.isEmpty() || list.get(list.size() - 1) != i) list.add(i);
                }
            }
        }

        private static long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xffffffffL);
        }

        /** True when the line passes within [r] metres of the point. */
        public boolean near(double lat, double lon, double r) {
            double x = lon * kx, y = lat * ky;
            long cx = (long) Math.floor(x / cell), cy = (long) Math.floor(y / cell);
            int reach = (int) Math.ceil(r / cell);
            for (long dx = -reach; dx <= reach; dx++) {
                for (long dy = -reach; dy <= reach; dy++) {
                    List<Integer> list = cells.get(key(cx + dx, cy + dy));
                    if (list == null) continue;
                    for (int i : list) {
                        double sx = p[2 * i + 1] * kx, sy = p[2 * i] * ky;
                        if (segDist(x - sx, y - sy, p[2 * i + 3] * kx - sx, p[2 * i + 2] * ky - sy) <= r) return true;
                    }
                }
            }
            return false;
        }
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        double x = Math.toRadians(lo2 - lo1) * Math.cos(Math.toRadians((la1 + la2) / 2));
        double y = Math.toRadians(la2 - la1);
        return Math.sqrt(x * x + y * y) * 6371000.0;
    }

    // ------------------------------------------------------------------ PC test

    /** Tries real trips around Iraq against the routing pack and prints what the chooser would offer. */
    public static void main(String[] args) {
        GraphHopper h = new GraphHopper().forMobile();
        h.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest"));
        h.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
        h.getCHPreparationHandler().setDisablingAllowed(true);
        h.setAllowWrites(false);
        if (!h.load(args[0])) {
            System.out.println("could not load " + args[0]);
            return;
        }
        Object[][] trips = {
            {"Baghdad -> Basra", 33.3152, 44.3661, 30.5085, 47.7804},
            {"Baghdad -> Erbil", 33.3152, 44.3661, 36.1911, 44.0092},
            {"Mosul -> Erbil", 36.3400, 43.1300, 36.1911, 44.0092},
            {"Karbala -> Najaf", 32.6160, 44.0240, 32.0000, 44.3350},
            {"Baghdad Mansour -> Jadriya", 33.3230, 44.3460, 33.2800, 44.3830},
            {"Baghdad Palestine St -> Airport", 33.3406, 44.4009, 33.2620, 44.2340},
            {"Basra -> Zubair", 30.5085, 47.7804, 30.3900, 47.7000},
            {"Hilla -> Diwaniya", 32.4800, 44.4300, 31.9900, 44.9300},
        };
        for (Object[] t : trips) {
            long t0 = System.nanoTime();
            GHResponse r = h.route(new GHRequest((double) t[1], (double) t[2], (double) t[3], (double) t[4])
                .setProfile("car").setLocale(Locale.ENGLISH));
            if (r.hasErrors()) {
                System.out.println(t[0] + ": " + r.getErrors());
                continue;
            }
            ResponsePath main = r.getBest();
            long t1 = System.nanoTime();
            List<ResponsePath> alts = find(h, main, 2, 3000);
            long t2 = System.nanoTime();
            System.out.printf("%s  (main %d ms, alternatives %d ms)%n", t[0], (t1 - t0) / 1000000, (t2 - t1) / 1000000);
            print("  main", main, null);
            double[] mp = points(main.getPoints());
            for (ResponsePath a : alts) print("  alt ", a, mp);
        }
    }

    private static void print(String label, ResponsePath p, double[] main) {
        Map<String, Double> byName = new LinkedHashMap<>();
        for (Instruction in : p.getInstructions()) {
            String name = in.getName() == null ? "" : in.getName().trim();
            if (!name.isEmpty()) byName.merge(name, in.getDistance(), Double::sum);
        }
        List<Map.Entry<String, Double>> top = new ArrayList<>(byName.entrySet());
        top.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            names.append(i == 0 ? "" : " / ").append(top.get(i).getKey()).append(" ").append(Math.round(top.get(i).getValue() / 1000)).append("km");
        }
        String shared = "";
        if (main != null) {
            double[] ap = points(p.getPoints());
            shared = String.format(Locale.US, "  shared %.0f%% / %.0f%%", overlap(ap, main) * 100, overlap(main, ap) * 100);
        }
        System.out.printf(Locale.US, "%s %.1f km  %d min%s  via %s%n", label, p.getDistance() / 1000, p.getTime() / 60000, shared, names);
    }
}
