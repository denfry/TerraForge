# Towny integration

## Division of responsibility

| | Responsibility |
|---|---|
| **TerraForge** | terrain, geography, coordinates, distances |
| **Towny** | towns, claims, nations, residents, permissions |
| **Players** | building everything |

TerraForge never creates a town, claims a plot or places a block. It **annotates** the towns players
create with real-world geography.

## Requirements

Towny installed, `towny.enabled: true`. Towny is a soft dependency: without it the module is not
loaded and the rest of TerraForge is unaffected.

## What it adds

When a town is created or its spawn moves, TerraForge resolves and stores:

- latitude / longitude of the spawn
- elevation
- country and region

Stored in the `town_geography` table, keyed by town UUID. Towny's own data is never modified.

## API

```java
TownGeoService service = ...;   // available only when Towny is present

Optional<EarthLocation> location = service.getEarthLocation(town);
Optional<Country> country = service.getCountry(town);
Optional<Region> region = service.getRegion(town);

double meters = service.getDistance(townA, townB);              // WGS84 geodesic
double toBerlin = service.getDistanceToCoordinates(town, 52.52, 13.405);
```

Distances are **real-world geodesic metres**, never block distance — at 1 block/km those differ by
three orders of magnitude.

## Events

`TownGeoUpdateEvent` fires after a town's geography is resolved or refreshed, so other plugins can
react (for example, to announce "New town founded in Bavaria, Germany").

## What it deliberately does not do

- no automatic town creation
- no automatic claims
- no generated buildings or roads for towns
- no changes to Towny's economy, permissions or war rules
