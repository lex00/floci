# Amazon Location Service

**Protocol:** REST JSON (SigV4 service `geo`)
**Endpoint:** `http://localhost:4566/geofencing/v0/*`, `/tracking/v0/*`, `/maps/v0/*`, `/places/v0/*`, `/routes/v0/*`, `/tags/{ResourceArn}`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateGeofenceCollection` | Create a geofence collection |
| `DescribeGeofenceCollection` | Read a geofence collection by name |
| `UpdateGeofenceCollection` | Change a geofence collection's description and settings |
| `DeleteGeofenceCollection` | Delete a geofence collection |
| `ListGeofenceCollections` | List geofence collection summaries |
| `CreateTracker` | Create a tracker |
| `DescribeTracker` | Read a tracker by name |
| `UpdateTracker` | Change a tracker's description and settings |
| `DeleteTracker` | Delete a tracker |
| `ListTrackers` | List tracker summaries |
| `CreateMap` | Create a map |
| `DescribeMap` | Read a map by name |
| `UpdateMap` | Change a map's description and settings |
| `DeleteMap` | Delete a map |
| `ListMaps` | List map summaries |
| `CreatePlaceIndex` | Create a place index |
| `DescribePlaceIndex` | Read a place index by name |
| `UpdatePlaceIndex` | Change a place index's description and settings |
| `DeletePlaceIndex` | Delete a place index |
| `ListPlaceIndexes` | List place index summaries |
| `CreateRouteCalculator` | Create a route calculator |
| `DescribeRouteCalculator` | Read a route calculator by name |
| `UpdateRouteCalculator` | Change a route calculator's description and settings |
| `DeleteRouteCalculator` | Delete a route calculator |
| `ListRouteCalculators` | List route calculator summaries |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->

## Emulation Behavior

- Geofence collections, trackers, maps, place indexes and route calculators are stored
  per region by name. A second create with the same name fails with
  `ConflictException`. The ARN resource is `geofence-collection/`, `tracker/`, `map/`,
  `place-index/` or `route-calculator/` followed by the name.
- A map's `DataSource` comes from its style name, such as `Here` for
  `VectorHereExplore`. A place index without `DataSourceConfiguration` reads back with
  `IntendedUse` `SingleUse`.
- Geofences, device positions, map tiles, place search and route calculation are not
  implemented, so a collection's `GeofenceCount` stays at 0.

## Host prefixes

The AWS SDKs put a host prefix in front of the endpoint for each Location operation:
`cp.geofencing.`, `cp.tracking.`, `cp.maps.`, `cp.places.`, `cp.routes.` and
`cp.metadata.` for tags. Against `http://localhost:4566` or an IP address, the prefixed
name does not resolve and the call never reaches Floci. Floci routes these operations by
path, so either of these works:

- Use a hostname with wildcard DNS, such as `http://localhost.floci.io:4566`. The
  prefixed name `cp.geofencing.localhost.floci.io` resolves to `127.0.0.1`.
- Turn host prefixes off in the client. The AWS CLI and boto3 read
  `AWS_DISABLE_HOST_PREFIX_INJECTION=true` (or `disable_host_prefix_injection = true` in
  the config file). The Java SDK takes
  `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION` on the client's override
  configuration.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_LOCATION_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost.floci.io:4566

aws location create-geofence-collection --collection-name depots
aws location describe-geofence-collection --collection-name depots
aws location create-map --map-name city --configuration Style=VectorEsriStreets
aws location list-geofence-collections
aws location delete-geofence-collection --collection-name depots
```
