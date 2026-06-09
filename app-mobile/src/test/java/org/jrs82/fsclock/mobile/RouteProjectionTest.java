package org.jrs82.fsclock.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RouteProjectionTest {

    @Test
    public void stopDistancesChoosesLaterOccurrenceOnLoop() {
        List<double[]> shape = Arrays.asList(
                point(60.0000, 24.0000),
                point(60.0000, 24.0100),
                point(60.0100, 24.0100),
                point(60.0100, 24.0000),
                point(60.0000, 24.0000));
        List<TimelineStop> stops = Arrays.asList(
                stop(60.0000, 24.0000),
                stop(60.0000, 24.0100),
                stop(60.0000, 24.0000));

        double[] cumulative = RouteProjection.cumulative(shape);
        int[] vertices = new int[stops.size()];
        double[] distances = RouteProjection.stopDistances(shape, cumulative, stops, vertices);

        assertEquals(0.0, distances[0], 0.01);
        assertTrue(distances[1] > distances[0]);
        assertEquals(cumulative[cumulative.length - 1], distances[2], 1.0);
        assertTrue(vertices[2] >= 3);
    }

    @Test
    public void projectHonorsMinimumProgress() {
        List<double[]> shape = Arrays.asList(
                point(60.0000, 24.0000),
                point(60.0000, 24.0100),
                point(60.0000, 24.0000));
        double[] cumulative = RouteProjection.cumulative(shape);

        double[] projected = RouteProjection.project(
                shape, cumulative, 60.0000, 24.0000, 0, shape.size() - 1,
                cumulative[1] + 1.0);

        assertTrue(Double.isFinite(projected[0]));
        assertTrue(projected[0] >= cumulative[1] + 1.0);
        assertEquals(cumulative[cumulative.length - 1], projected[0], 1.0);
    }

    @Test
    public void projectRejectsInvalidCoordinate() {
        List<double[]> shape = Arrays.asList(
                point(60.0000, 24.0000),
                point(60.0000, 24.0100));
        double[] projected = RouteProjection.project(
                shape, RouteProjection.cumulative(shape), Double.NaN, 24.0, 0, 1);

        assertTrue(Double.isNaN(projected[0]));
        assertTrue(Double.isInfinite(projected[1]));
        assertEquals(-1.0, projected[2], 0.0);
    }

    private static double[] point(double lat, double lon) {
        return new double[]{lat, lon};
    }

    private static TimelineStop stop(double lat, double lon) {
        return new TimelineStop("id", "name", "code", 0L, false, lat, lon);
    }
}
