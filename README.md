Entropy Data Connector for Databricks
===

The connector for Databricks is a Spring Boot application that uses the [entropy-data-sdk](https://github.com/entropy-data/entropy-data-sdk) internally, and is available as a ready-to-use Docker image [entropydata/entropy-data-connector-databricks](https://hub.docker.com/r/entropydata/entropy-data-connector-databricks) to be deployed in your environment.

## Features

- **Asset Synchronization**: Sync tables and schemas of the Unity catalog to Entropy Data as Assets. 
- **Access Management**: Listen for AccessActivated and AccessDeactivated events in Entropy Data and grants access on Databricks to the data consumer.

## Usage

Start the connector using Docker. You must pass the API keys as environment variables.

```
docker run \
  -e ENTROPYDATA_CLIENT_APIKEY='insert-api-key-here' \
  -e ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_HOST='https://dbc-xxxxxx.cloud.databricks.com/' \
  -e ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_CLIENTID='your-client-id' 
  -e ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_CLIENTSECRET='your-client-secret'
  -e ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_HOST='https://accounts.cloud.databricks.com' \
  -e ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_ACCOUNTID='your-account-id' \
  -e ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_CLIENTID='your-account-client-id' \
  -e ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_CLIENTSECRET='your-account-client-secret' \
  entropydata/entropy-data-connector-databricks:latest
```

## Versions

Every release is published as an immutable image tag. Pin a version rather than following `latest`:

```
entropydata/entropy-data-connector-databricks:0.3.0
```

| Tag | Meaning |
|---|---|
| `X.Y.Z` | A released version. Immutable, and the recommended way to run the connector. |
| `latest` | The most recent release. Moves with every release. |
| `sha-<commit>` | A single commit on `main`, published so that a change can be tried out before it is released. |

Release images are signed with [cosign](https://docs.sigstore.dev/), and carry an SBOM and build provenance.
Verifying needs **cosign 3 or later**, because the signatures use the OCI referrers format that cosign 2 cannot read
(it reports `no signatures found`):

```
cosign verify entropydata/entropy-data-connector-databricks:0.3.0 \
  --certificate-identity-regexp 'https://github.com/entropy-data/entropy-data-connector-databricks/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## Configuration

| Environment Variable                                                                 | Default Value                      | Description                                                                                                                          |
|--------------------------------------------------------------------------------------|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `ENTROPYDATA_CLIENT_HOST`                                                            | `https://api.entropy-data.com`     | Base URL of the Entropy Data API.                                                                                                    |
| `ENTROPYDATA_CLIENT_APIKEY`                                                          |                                    | API key for authenticating requests to Entropy Data.                                                                                |
| `ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_HOST`                                       |                                    | Databricks workspace host URL in the form of `https://dbc-xxxxxx.cloud.databricks.com` (for AWS).                                    |
| `ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_CLIENTID`                                   |                                    | Client ID of a workspace service principal with USE CATALOG, USE SCHEMA, SELECT, and MODIFY permissions to grant permissions to schemas.     |
| `ENTROPYDATA_CLIENT_DATABRICKS_WORKSPACE_CLIENTSECRET`                               |                                    | Client secret of a workspace service principal with USE CATALOG, USE SCHEMA, SELECT, and MODIFY permissions to grant permissions to schemas. |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_HOST`                                         |                                    | Databricks account login URL, e.g. the form of `https://accounts.cloud.databricks.com` (for AWS).                                    |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_ACCOUNTID`                                    |                                    | The databricks Account ID.                                                                                                           |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_CLIENTID`                                     |                                    | The client ID of a an account service principal with Account admin role.                                                             |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCOUNT_CLIENTSECRET`                                 |                                    | The client secret of a an account service principal with Account admin role.                                                         |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCESSMANAGEMENT_CONNECTORID`                         | `databricks-access-management`     | Identifier for the Databricks access management connector.                                                                               |
| `ENTROPYDATA_CLIENT_DATABRICKS_ACCESSMANAGEMENT_ENABLED`                             | `true`                             | Indicates whether Databricks access management is enabled.                                                                           |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_CONNECTORID`                                   | `databricks-assets`                | Identifier for the Databricks assets connector.                                                                                          |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_ENABLED`                                       | `true`                             | Indicates whether Databricks asset tracking is enabled.                                                                              |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_POLLINTERVAL`                                  | `PT10M`                            | Polling interval for Databricks asset updates, in ISO 8601 duration format.                                                          |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_OWNER`                                         |                                    | The ID of the Entropy Data team that owns new assets, e.g. `data-platform`. This is the team's ID shown on its page in Entropy Data and used as `owner` in the API, not the team's display name. An owner set in Entropy Data is kept. Empty means no owner. The connector does not start if no team has this ID. |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_CATALOGS_INCLUDE`                              |                                    | Comma-separated glob patterns of catalog names to synchronize, e.g. `prod,analytics_*`. Empty means all catalogs.                    |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_CATALOGS_EXCLUDE`                              |                                    | Comma-separated glob patterns of catalog names to skip, e.g. `dev_*,staging`. Applied after the include patterns.                    |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_SCHEMAS_INCLUDE`                               |                                    | Comma-separated glob patterns of schema names to synchronize. Empty means all schemas.                                               |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_SCHEMAS_EXCLUDE`                               |                                    | Comma-separated glob patterns of schema names to skip, e.g. `tmp_*`. Applied after the include patterns.                             |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_TABLES_INCLUDE`                                |                                    | Comma-separated glob patterns of table names to synchronize. Empty means all tables.                                                 |
| `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_TABLES_EXCLUDE`                                |                                    | Comma-separated glob patterns of table names to skip, e.g. `*_backup`. Applied after the include patterns.                           |

Patterns are matched case-insensitively against the plain catalog, schema, or table name, not against the fully qualified name.
Only managed Unity Catalog catalogs are synchronized, and the `information_schema` is always skipped.

### Scoping the Synchronization

Large workspaces synchronize faster, and use less memory, when development or staging catalogs are excluded:

```
-e ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_CATALOGS_EXCLUDE='dev_*,staging'
```

Catalogs are filtered before their schemas and tables are listed, so excluded catalogs cost no API calls at all.

### Owners and Assets Created in Entropy Data

Entropy Data only lets assets with an owner be edited, for example to assign tags. Set `ENTROPYDATA_CLIENT_DATABRICKS_ASSETS_OWNER`
to the ID of a team to give new assets an owner. The ID is shown on the team's page in Entropy Data, for example
`data-platform`; the team's display name does not work. An owner the asset already has in Entropy Data, for example set
through the API, is kept: the connector's owner only fills an empty one. Tags and links set in Entropy Data are kept as well.

An asset can be created in Entropy Data before its table exists in Databricks, for example to assign it to an output port
upfront. When the table appears, the connector updates that asset instead of creating a second one, provided the asset has:

| Field                | Value                                                                  |
|----------------------|------------------------------------------------------------------------|
| `info.source`        | `unity`                                                                |
| `info.qualifiedName` | `<catalog>.<schema>.<table>` (compared ignoring case)                  |
| `info.sourceId`      | not set; the connector sets it to the Unity Catalog table id           |
| `id`                 | any                                                                    |

The asset keeps its `id`. Schemas (`<catalog>.<schema>`) and catalogs work the same way. If more than one asset matches,
the connector updates none of them and creates its own. Create the asset before the table: once the connector has
synchronized a table, it keeps updating its own asset. This needs an Entropy Data version that supports filtering
`GET /api/assets` by `source` and `qualifiedName`.

## Resources

The connector needs **at least 1 GB of container memory**. The image sets a heap limit accordingly:

```
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError
```

Without `MaxRAMPercentage`, the JVM caps the heap at 25% of the container memory, which is not enough to synchronize large
Unity catalogs. `ExitOnOutOfMemoryError` terminates the container instead of leaving it running with a dead synchronization
thread, so that your orchestrator can restart it.

Setting `JAVA_TOOL_OPTIONS` at runtime **replaces** these flags rather than adding to them. Repeat the flags you want to keep:

```
-e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -javaagent:/agent.jar'
```

Expect the container to use around 60% of its memory limit under load. Adjust memory alarms accordingly.

### Synchronization Health

The health endpoint reports whether the asset synchronization is still up to date:

```
curl http://localhost:8080/actuator/health
```

The `assetsSynchronizationHealth` component reports `DEGRADED` when the last run failed, or when no run has succeeded for three
poll intervals, and names the failure in `lastFailure`. It is deliberately not reported as `DOWN`, and the endpoint still responds
with 200, because the usual cause is an unavailable data platform, which restarting the container does not fix. Point liveness
probes at `/actuator/health/liveness`, which is unaffected by the synchronization state.


## Access Management Flow

When an Access Request has been approved by the data product owner, and the start date is reached, Entropy Data will publish an `AccessActivatedEvent`. When an end date is defined and reached, Entropy Data will publish an `AccessDeactivatedEvent`. The connector listens for these events and grants access to the data consumer in Databricks.

The Databricks server (host, catalog, and schema) is resolved from the data contract linked by the provider output port.
The linked data contract must be in ODCS (Open Data Contract Standard) format; the legacy Data Contract Specification (DCS) is not supported.

### Consumer Type: Data Product

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`. 
- The linked data contract defines the Databricks server with catalog `my_catalog` and schema `schema_220`.
- Consumer is a data product with ID `c-300`.
- Access ID is `a-100`.

Connector Actions on `AccessActivatedEvent`:

- Create a new service principal `dataproduct-c-300`, if it does not exist. (if a custom field `databricksServicePrincipal` is defined in the data product, the value will be used as the service principal name instead of the ID)
- Create a new group `access-a-100` for this access.
- Add the service principal `dataproduct-c-300` to the group `access-a-100`.
- Create a new group `team-t-300`, if it does not exist. (if a custom field `databricksGroupName` is defined in the team, the value will be used as the group name instead of the ID)
- Add all members of the team `t-300` to the group `team-t-300`.
- Add the group `team-t-300` to the group `access-a-101`.
- Grant permissions `USE SCHEMA` and `SELECT` on the schema `my_catalog.schema_220` to group `access-a-100`

Connector Actions on `AccessDeactivatedEvent`:

- Delete the group `access-a-100`


### Consumer Type: Team

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The linked data contract defines the Databricks server with catalog `my_catalog` and schema `schema_220`.
- Consumer is a team with ID `t-400`.
- Access ID is `a-101`.

Connector Actions on `AccessActivatedEvent`:

- Create a new group `team-t-400`, if it does not exist. (if a custom field `databricksGroupName` is defined in the team, the value will be used as the group name instead of the ID)
- Add all members of the team `t-400` to the group `team-t-400`.
- Create a new group `access-a-101` for this access.
- Add the group `team-t-400` to the group `access-a-101`.
- Grant permissions `USE SCHEMA` and `SELECT` on the schema `my_catalog.schema_220` to group `access-a-101`

Connector Actions on `AccessDeactivatedEvent`:

- Delete the group `access-a-101`


### Consumer Type: User

Example:

- Provider is a data product with ID `p-200` and selected output port `p-200-op-210`.
- The linked data contract defines the Databricks server with catalog `my_catalog` and schema `schema_220`.
- Consumer is an individual user with username `alice@example.com`.
- Access ID is `a-102`.

Connector Actions on `AccessActivatedEvent`:

- Create a new group `access-a-102` for this access.
- Add the user `alice@example.com` to the group `access-a-102` (the connector currently assumes that the username in Entropy Data and Databricks are equal).
- Grant permissions `USE SCHEMA` and `SELECT` on the schema `my_catalog.schema_220` to group `access-a-102`

Connector Actions on `AccessDeactivatedEvent`:

- Delete the group `access-a-102`

