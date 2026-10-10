package org.jrs82.fsclock.mobile;

import androidx.annotation.StringRes;

import org.jrs82.fsclock.R;

final class TrafficNotice {

    enum Kind {
        ALL("", R.string.transit_traffic_all),
        ACCIDENT("traffic-announcements", R.string.transit_traffic_accidents),
        ROAD_WORK("roadworks", R.string.transit_traffic_roadworks),
        WEIGHT_RESTRICTION("weight-restrictions", R.string.transit_traffic_weight_restrictions),
        INCIDENT("traffic-announcements", R.string.transit_traffic_incidents),
        CONGESTION("traffic-announcements", R.string.transit_traffic_congestion);

        final String endpoint;
        @StringRes final int titleRes;

        Kind(String endpoint, @StringRes int titleRes) {
            this.endpoint = endpoint;
            this.titleRes = titleRes;
        }

        boolean isConcrete() {
            return endpoint != null && !endpoint.isEmpty();
        }
    }

    final Kind kind;
    final String id;
    final String title;
    final String location;
    final String details;
    final String severity;
    final long startTimeMs;
    final long endTimeMs;
    final double distanceMeters;

    TrafficNotice(Kind kind, String id, String title, String location, String details,
                  String severity, long startTimeMs, long endTimeMs, double distanceMeters) {
        this.kind = kind;
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.location = location == null ? "" : location;
        this.details = details == null ? "" : details;
        this.severity = severity == null ? "" : severity;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.distanceMeters = distanceMeters;
    }
}
