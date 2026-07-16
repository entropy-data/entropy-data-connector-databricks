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


## Access Management Flow

When an Access Request has been approved by the data product owner, and the start date is reached, Entropy Data will publish an `AccessActivatedEvent`. When an end date is defined and reached, Entropy Data will publish an `AccessDeactivatedEvent`. The connector listens for these events and grants access to the data consumer in Databricks.

The Databricks server (host, catalog, and schema) is resolved from the data contract linked by the provider output port.

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

