# BlueMap integration

## Scope

BlueMap already renders the world and, together with Towny's own integration, already shows town and
nation areas. TerraForge does not replace or duplicate any of that.

TerraForge adds only what the others cannot know:

- real-world city and capital labels
- country and region labels
- real geographic coordinates for towns

## Requirements

BlueMap installed, `bluemap.enabled: true`. BlueMap is a soft dependency: when it is absent, the
hook is never instantiated and TerraForge runs normally.

```yaml
bluemap:
  enabled: true
  city-markers: true
  country-labels: true
```

## Marker sets

| Set | Marker types | Config |
|---|---|---|
| `terraforge-cities` | `CITY`, `CAPITAL` — populated places from the gazetteer | `city-markers` |
| `terraforge-countries` | `COUNTRY`, `REGION` — administrative labels | `country-labels` |
| `terraforge-poi` | `POINT_OF_INTEREST`, `CUSTOM` — registered through the API by other plugins | always on |

Every set TerraForge owns starts with `terraforge-`. A set with no markers is not created at all.

`TOWN` and `NATION` markers are **never** published: Towny already puts those on the map itself, and
publishing them again would give every town two labels.

Markers are **metadata**: a label at a coordinate. Registering one never places a block, structure
or entity in the world. If Towny already publishes a marker for something, TerraForge leaves it
alone.

A marker sits at the real surface height of its coordinate, or at sea level when the surface is
below it, so coastal and island labels stay above water.

### How many cities are published

The gazetteer can hold hundreds of thousands of places, which would make the web map unusable. Every
**capital** is published unconditionally; other cities are ranked by population and capped at the
2000 largest. Ties break by name, so the published set is the same on every start.

## Marker API

Other plugins can register their own geographic markers, with or without BlueMap installed:

```java
GeoMarkerService markers = ...;

markers.register(new GeoMarkerService.GeoMarker(
        "myplugin:harbour",
        "Old Harbour",
        GeoMarkerService.MarkerType.POINT_OF_INTEREST,
        GeoPoint.of(53.5459, 9.9695),
        "Player-built harbour"));
```

When BlueMap is missing, markers are still registered — they simply have no renderer, and appear as
soon as BlueMap is installed.

## Behaviour on BlueMap reload

BlueMap discards marker sets when it reloads. The hook registers a `BlueMapAPI.onEnable` listener,
which fires once when BlueMap first becomes ready and again after every reload; each time it removes
only the sets whose id starts with `terraforge-` and publishes them again from the registry. Sets
owned by other plugins are never read, replaced or removed.

The same happens on `/earth reload`: the marker registry is rebuilt from the prepared database and
re-published. Markers are metadata, so this is safe at runtime — unlike terrain settings, which
still require a restart.
