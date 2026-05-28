package org.jrs82.fsclock.mobile;

final class TrafficNotice {

    enum Kind {
        ALL("", "Liikennetiedot"),
        ACCIDENT("traffic-announcements", "Onnettomuudet"),
        ROAD_WORK("roadworks", "Tietyöt"),
        WEIGHT_RESTRICTION("weight-restrictions", "Painorajoitukset"),
        INCIDENT("traffic-announcements", "Häiriöt"),
        CONGESTION("traffic-announcements", "Ruuhkat");

        final String endpoint;
        final String title;

        Kind(String endpoint, String title) {
            this.endpoint = endpoint;
            this.title = title;
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
