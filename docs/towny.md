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

TerraForge resolves and stores, per town:

- latitude / longitude of the spawn
- elevation
- country and region

Stored in the `town_geography` table, keyed by town UUID. Towny's own data is never modified.

The annotation is kept in step with Towny by observing four events, all at `MONITOR` priority —
TerraForge never influences whether a town is created, moved or deleted:

| Event | Effect |
|---|---|
| `NewTownEvent` | annotate the new town |
| `TownSetSpawnEvent` | re-annotate: a moved spawn changes coordinates, country and elevation |
| `RenameTownEvent` | write the new display name through |
| `DeleteTownEvent` | forget the town |

## Backfilling existing towns

Towns that already existed before TerraForge was installed have no annotation, because no event
ever fired for them:

```
/earth towny refresh          # every town Towny knows
/earth towny refresh Berlin   # one town
/earth towny status           # how many writes are still queued
```

Requires `terraforge.command.towny` (op by default). A town without a spawn is skipped — there is
nothing to locate.

## Threading

Reading Towny is a main-thread operation; writing SQLite is a disk write and must not be. So the
town is snapshotted on the server thread into a plain record, and only that record crosses to a
single writer thread that owns one connection. One thread and one queue means writes keep their
order: a rename followed by a delete can never land the other way round.

`/earth towny refresh` over a large server therefore returns immediately and drains in the
background; `/earth towny status` shows the backlog. On shutdown the queue is flushed before the
connection closes. If the queue ever fills up, the write is refused with a log line rather than
silently dropped.

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

## What it deliberately does not do

- no automatic town creation
- no automatic claims
- no generated buildings or roads for towns
- no changes to Towny's economy, permissions or war rules
