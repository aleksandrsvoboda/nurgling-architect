package architect.gen;

import java.util.Random;

/** Seeded 2D gradient (Perlin) noise and fractal sums of it. Values roughly in [-1, 1]. */
public class Noise {
    private final int[] perm = new int[512];
    private static final double[][] GRAD = new double[16][];
    static {
        for(int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16;
            GRAD[i] = new double[] {Math.cos(a), Math.sin(a)};
        }
    }

    public Noise(long seed) {
        int[] p = new int[256];
        for(int i = 0; i < 256; i++)
            p[i] = i;
        Random r = new Random(seed);
        for(int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for(int i = 0; i < 512; i++)
            perm[i] = p[i & 255];
    }

    private static double fade(double t) {
        return(t * t * t * (t * (t * 6 - 15) + 10));
    }

    private double dot(int hash, double x, double y) {
        double[] g = GRAD[hash & 15];
        return(g[0] * x + g[1] * y);
    }

    public double noise(double x, double y) {
        int xi = (int)Math.floor(x), yi = (int)Math.floor(y);
        double xf = x - xi, yf = y - yi;
        int X = xi & 255, Y = yi & 255;
        int aa = perm[perm[X] + Y], ab = perm[perm[X] + Y + 1], ba = perm[perm[X + 1] + Y], bb = perm[perm[X + 1] + Y + 1];
        double u = fade(xf), v = fade(yf);
        double x1 = lerp(dot(aa, xf, yf), dot(ba, xf - 1, yf), u);
        double x2 = lerp(dot(ab, xf, yf - 1), dot(bb, xf - 1, yf - 1), u);
        return(lerp(x1, x2, v) * 1.414);
    }

    private static double lerp(double a, double b, double t) {
        return(a + (b - a) * t);
    }

    /** Fractal sum: octaves of noise, each twice the frequency and half the weight. */
    public double fbm(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0;
        for(int i = 0; i < octaves; i++) {
            sum += noise(x, y) * amp;
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
        }
        return(sum / norm);
    }

    /** Ridged fractal: sharp crests, for mountain ranges. In [0, 1]. */
    public double ridged(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0;
        for(int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise(x, y));
            sum += n * n * amp;
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
        }
        return(sum / norm);
    }
}
