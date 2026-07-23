package entropydata.databricks;

import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.service.catalog.CatalogInfo;
import com.databricks.sdk.service.catalog.CatalogType;
import com.databricks.sdk.service.catalog.ListCatalogsRequest;
import com.databricks.sdk.service.catalog.SchemaInfo;
import com.databricks.sdk.service.catalog.TableInfo;
import entropydata.sdk.EntropyDataAssetsProvider;
import entropydata.sdk.EntropyDataStateRepository;
import entropydata.sdk.client.model.Asset;
import entropydata.sdk.client.model.AssetColumn;
import entropydata.sdk.client.model.AssetInfo;
import entropydata.sdk.client.model.AssetRelationshipsInner;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabricksAssetsSupplier implements EntropyDataAssetsProvider {

  private static final Logger log = LoggerFactory.getLogger(DatabricksAssetsSupplier.class);

  private final WorkspaceClient workspaceClient;
  private final EntropyDataStateRepository entropyDataStateRepository;
  private final DatabricksProperties databricksProperties;

  public DatabricksAssetsSupplier(WorkspaceClient workspaceClient, EntropyDataStateRepository entropyDataStateRepository,
      DatabricksProperties databricksProperties) {
    this.workspaceClient = workspaceClient;
    this.entropyDataStateRepository = entropyDataStateRepository;
    this.databricksProperties = databricksProperties;
  }

  @Override
  public void fetchAssets(AssetCallback assetCallback) {
    final var databricksLastUpdatedAt = getLastUpdatedAt();
    var databricksLastUpdatedAtThisRunMax = databricksLastUpdatedAt;

    var catalogs = workspaceClient.catalogs().list(new ListCatalogsRequest());
    for (var catalog : catalogs) {
      if (!includeCatalog(catalog)) {
        continue;
      }

      log.info("Synchronizing catalog {}", catalog.getFullName());
      catalogToAsset(catalog, databricksLastUpdatedAt).ifPresent(assetCallback::onAssetUpdated);

      var schemas = workspaceClient.schemas().list(catalog.getFullName());
      long schemasCount = 0;
      for (var schema : schemas) {
        if (!includeSchema(schema)) {
          continue;
        }

        log.info("Synchronizing schema {}", schema.getFullName());
        schemaToAsset(schema, catalog, databricksLastUpdatedAt).ifPresent(assetCallback::onAssetUpdated);

        var tables = workspaceClient.tables().list(schema.getCatalogName(), schema.getName());
        long tablesCount = 0;
        for (var table : tables) {
          tableToAsset(table, schema, databricksLastUpdatedAt).ifPresent(assetCallback::onAssetUpdated);

          databricksLastUpdatedAtThisRunMax = Math.max(databricksLastUpdatedAtThisRunMax, table.getUpdatedAt());
          tablesCount++;
        }
        log.info("Synchronized {} tables in schema {}", tablesCount, schema.getFullName());
        schemasCount++;
      }
      log.info("Synchronized {} schemas in catalog {}", schemasCount, catalog.getFullName());
    }

    setLastUpdatedAt(databricksLastUpdatedAtThisRunMax);
  }

  private Long getLastUpdatedAt() {
    Map<String, Object> state = entropyDataStateRepository.getState();
    var lastUpdatedAt = state.get("lastUpdatedAt");
    if (lastUpdatedAt == null) {
      return 0L;
    }
    if (lastUpdatedAt instanceof Long) {
      return (Long) lastUpdatedAt;
    }

    if (lastUpdatedAt instanceof Integer) {
      return ((Integer) lastUpdatedAt).longValue();
    }

    if (lastUpdatedAt instanceof String) {
      try {
        return Long.parseLong((String) lastUpdatedAt);
      } catch (NumberFormatException e) {
        log.warn("Failed to parse lastUpdatedAt from state: {}", lastUpdatedAt, e);
        return 0L;
      }
    }

    return 0L;
  }

  private void setLastUpdatedAt(Long databricksLastUpdatedAtThisRunMax) {
    Map<String, Object> state = Map.of("lastUpdatedAt", databricksLastUpdatedAtThisRunMax);
    entropyDataStateRepository.saveState(state);
  }

  private Optional<Asset> catalogToAsset(CatalogInfo catalog, Long databricksLastUpdatedAt) {
    if (!includeCatalog(catalog)) {
      log.debug("Skipping catalog {}", catalog.getFullName());
      return Optional.empty();
    }

    if (alreadySynchronized(catalog, databricksLastUpdatedAt)) {
      log.info("Catalog {} already synchronized", catalog.getFullName());
      return Optional.empty();
    }

    log.info("Synchronizing catalog {}", catalog.getFullName());

    Asset asset = new Asset()
        .id(getCatalogNameAsIdAsWorkaround(catalog))
        .info(new AssetInfo()
            .name(catalog.getName())
            .source("unity")
            .qualifiedName(catalog.getFullName())
            .type("unity_catalog")
            .status("active")
            .description(catalog.getComment()))
        .putPropertiesItem("host", databricksProperties.workspace().host())
        .putPropertiesItem("catalogType", catalog.getCatalogType().toString())
        .putPropertiesItem("updatedAt", catalog.getUpdatedAt().toString());

    return Optional.of(asset);
  }

  private static String getCatalogNameAsIdAsWorkaround(CatalogInfo catalog) {
    // TODO use catalog id when it becomes available
    return catalog.getName();
  }


  protected Optional<Asset> schemaToAsset(SchemaInfo schema, CatalogInfo catalog, Long databricksLastUpdatedAt) {

    if (!includeSchema(schema)) {
      log.debug("Skipping schema {}", schema.getFullName());
      return Optional.empty();
    }

    if (alreadySynchronized(schema, databricksLastUpdatedAt)) {
      log.info("Schema {} already synchronized", schema.getFullName());
      return Optional.empty();
    }

    log.info("Synchronizing schema {}", schema.getFullName());
    Asset asset = new Asset()
        .id(schema.getSchemaId())
        .info(new AssetInfo()
            .name(schema.getName())
            .source("unity")
            .qualifiedName(schema.getFullName())
            .type("unity_schema")
            .status("active")
            .description(schema.getComment()))
        .putPropertiesItem("host", databricksProperties.workspace().host())
        .putPropertiesItem("catalog", schema.getCatalogName())
        .putPropertiesItem("catalogType", schema.getCatalogType())
        .putPropertiesItem("schema", schema.getName())
        .relationships(List.of(new AssetRelationshipsInner().relationshipType("parent").assetId(getCatalogNameAsIdAsWorkaround(catalog))))
        .putPropertiesItem("updatedAt", schema.getUpdatedAt().toString());

    return Optional.of(asset);
  }

  protected Optional<Asset> tableToAsset(TableInfo table, SchemaInfo schema, Long databricksLastUpdatedAt) {
    if (!includeTable(table)) {
      log.debug("Skipping table {}", table.getFullName());
      return Optional.empty();
    }

    if (alreadySynchronized(table, databricksLastUpdatedAt)) {
      log.info("Table {} already synchronized", table.getFullName());
      return Optional.empty();
    }

    log.info("Synchronizing table {}", table.getFullName());

    Asset asset = new Asset()
        .id(table.getTableId())
        .info(new AssetInfo()
            .name(table.getName())
            .source("unity")
            .qualifiedName(table.getFullName())
            .type("unity_table")
            .status("active")
            .description(table.getComment()))
        .putPropertiesItem("host", databricksProperties.workspace().host())
        .putPropertiesItem("catalog", table.getCatalogName())
        .putPropertiesItem("schema", table.getSchemaName())
        .putPropertiesItem("table", table.getName())
        .putPropertiesItem("tableType", table.getTableType())
        .relationships(List.of(new AssetRelationshipsInner().relationshipType("parent").assetId(schema.getSchemaId())))
        .putPropertiesItem("updatedAt", table.getUpdatedAt().toString());

    if (table.getColumns() != null) {
      for (var column : table.getColumns()) {
        asset.addColumnsItem(new AssetColumn()
            .name(column.getName())
            .type(column.getTypeText())
            .description(column.getComment()));
      }
    }

    return Optional.of(asset);
  }

  protected boolean includeCatalog(CatalogInfo catalog) {
    if (catalog.getCatalogType() == CatalogType.MANAGED_CATALOG) {
      return true;
    }

    return false;
  }

  protected boolean includeSchema(SchemaInfo schema) {
    if (Objects.equals(schema.getName(), "information_schema")) {
      return false;
    }
    return true;
  }

  protected boolean includeTable(TableInfo table) {
    return true;
  }

  private boolean alreadySynchronized(CatalogInfo catalogInfo, Long databricksLastUpdatedAt) {
    return databricksLastUpdatedAt >= catalogInfo.getUpdatedAt();
  }

  private boolean alreadySynchronized(SchemaInfo schema, Long databricksLastUpdatedAt) {
    return databricksLastUpdatedAt >= schema.getUpdatedAt();
  }

  private boolean alreadySynchronized(TableInfo table, Long databricksLastUpdatedAt) {
    return databricksLastUpdatedAt >= table.getUpdatedAt();
  }


}
