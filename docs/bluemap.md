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

| Set | Contents | Config |
|---|---|---|
| `terraforge-cities` | populated places from the gazetteer | `city-markers` |
| `terraforge-countries` | country and region labels | `country-labels` |

Markers are **metadata**: a label at a coordinate. Registering one never places a block, structure
or entity in the world. If Towny already publishes a marker for something, TerraForge leaves it
alone.

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

BlueMap discards marker sets when it reloads. `TerraForgeBlueMapHook.refreshMarkers()` re-registers
TerraForge's sets afterwards; markers owned by other plugins are never touched.
